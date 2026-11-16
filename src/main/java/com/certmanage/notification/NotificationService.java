package com.certmanage.notification;

import static com.certmanage.common.Errors.require;

import com.certmanage.smtp.SmtpService;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * @description 管理证书到期策略及投递，禁用资产暂停新投递并保留历史。
 * @author Freddy
 * @date 2026-11-12
 */
public class NotificationService implements com.certmanage.common.PerformanceSource {
  private final Object scanLock = new Object();
  private volatile long scanMillis, sendMillis, scanCount, sendBatches;
  private volatile boolean scanning, sending;
  private final com.certmanage.common.ExecutionLog logs;
  private final JdbcTemplate db;
  private final SmtpService smtp;

  /**
   * @description 初始化通知服务依赖。
   * @author Freddy
   * @date 2026-11-12
   * @param d 数据库组件
   * @param s 邮件组件
   * @param logs 执行日志组件
   */
  public NotificationService(
      JdbcTemplate d, SmtpService s, com.certmanage.common.ExecutionLog logs) {
    db = d;
    smtp = s;
    this.logs = logs;
  }

  /**
   * @description 读取策略、提醒节点和收件人。
   * @author Freddy
   * @date 2026-11-12
   * @return 当前策略配置
   */
  public Map<String, Object> get() {
    var r = db.queryForMap("select * from notification_policy where id=1");
    return Map.of(
        "enabled",
        r.get("ENABLED"),
        "timezone",
        r.get("TIMEZONE_ID"),
        "scanTime",
        r.get("SCAN_TIME").toString(),
        "warningDays",
        r.get("WARNING_DAYS"),
        "includeCa",
        r.get("INCLUDE_CA"),
        "thresholds",
        db.queryForList(
            "select threshold_days from notification_thresholds order by threshold_days desc",
            Integer.class),
        "recipients",
        db.queryForList(
            "select email_address from notification_recipients order by id", String.class));
  }

  /**
   * @description 校验并保存策略，去重提醒节点和收件人。
   * @author Freddy
   * @date 2026-11-12
   * @param b 策略配置
   * @param actor 操作用户标识
   * @throws IllegalArgumentException 策略参数不合法或启用条件不满足时抛出
   */
  @Transactional
  public void save(Map<String, Object> b, long actor) {
    String zone = Objects.toString(b.get("timezone"), "Asia/Shanghai"),
        time = Objects.toString(b.get("scanTime"), "09:00");
    try {
      ZoneId.of(zone);
      LocalTime.parse(time);
    } catch (Exception e) {
      throw new IllegalArgumentException("时区或检查时间不合法");
    }
    int warning = ((Number) b.getOrDefault("warningDays", 30)).intValue();
    require(warning >= 0 && warning <= 3650, "预警天数超出范围");
    @SuppressWarnings("unchecked")
    List<Number> thresholds = (List<Number>) b.getOrDefault("thresholds", List.of(30, 14, 7, 1));
    @SuppressWarnings("unchecked")
    List<String> recipients = (List<String>) b.getOrDefault("recipients", List.of());
    require(!thresholds.isEmpty() && thresholds.size() <= 20, "请配置1-20个提醒节点");
    for (var t : thresholds) require(t.intValue() >= 0 && t.intValue() <= 3650, "提醒天数超出范围");
    for (String email : recipients) require(SmtpService.validEmail(email.trim()), "收件邮箱不合法");
    boolean enabled = Boolean.TRUE.equals(b.get("enabled"));
    if (enabled) {
      require(!recipients.isEmpty(), "启用前请填写收件人");
      require(
          db.queryForObject("select count(*) from smtp_config", Integer.class) > 0, "启用前请配置SMTP");
    }
    db.update(
        "update notification_policy set enabled=?,timezone_id=?,scan_time=?,warning_days=?,include_ca=?,updated_by=?,config_revision=config_revision+1,updated_at=current_timestamp where id=1",
        enabled,
        zone,
        LocalTime.parse(time),
        warning,
        Boolean.TRUE.equals(b.get("includeCa")),
        actor);
    db.update("delete from notification_thresholds");
    for (int days : thresholds.stream().mapToInt(Number::intValue).distinct().toArray())
      db.update("insert into notification_thresholds values(1,?)", days);
    db.update("delete from notification_recipients");
    var seen = new HashSet<String>();
    for (String email : recipients) {
      String clean = email.trim(), key = emailKey(clean);
      if (seen.add(key))
        db.update(
            "insert into notification_recipients(policy_id,email_address,email_key) values(1,?,?)",
            clean,
            key);
    }
  }

  /**
   * @description 保留邮箱本地部分，将域名转为小写用于去重。
   * @author Freddy
   * @date 2026-11-12
   * @param email 含域名分隔符的邮箱地址
   * @return 收件人去重键
   */
  public static String emailKey(String email) {
    int at = email.lastIndexOf('@');
    return email.substring(0, at) + email.substring(at).toLowerCase(Locale.ROOT);
  }

  /**
   * @description 扫描到期证书并记录扫描结果。
   * @author Freddy
   * @date 2026-11-12
   * @return 新建投递数量
   * @throws RuntimeException 扫描或日志写入失败时传播
   */
  public int scan() {
    synchronized (scanLock) {
      long started = System.nanoTime();
      scanning = true;
      try {
        return scanWithLogs();
      } finally {
        scanMillis = (System.nanoTime() - started) / 1_000_000;
        scanCount++;
        scanning = false;
      }
    }
  }

  /**
   * @description 执行单轮扫描并记录结果，不与邮件投递共享监视器。
   * @author Freddy
   * @date 2026-11-16
   * @return 本轮新增投递数量
   */
  private int scanWithLogs() {
    logs.append(
        "NOTIFICATION",
        "SCAN_STARTED",
        "STARTED",
        "notification-worker",
        null,
        null,
        "POLICY",
        1L,
        "到期通知扫描开始",
        Map.of());
    int count;
    try {
      count = scanPolicy();
    } catch (RuntimeException e) {
      logs.append(
          "NOTIFICATION",
          "SCAN_FAILURE",
          "FAILURE",
          "notification-worker",
          null,
          null,
          "POLICY",
          1L,
          "通知扫描失败",
          Map.of());
      throw e;
    }
    logs.append(
        "NOTIFICATION",
        "SCAN_SUCCESS",
        "SUCCESS",
        "notification-worker",
        null,
        null,
        "POLICY",
        1L,
        "到期通知扫描完成",
        Map.of("count", count));
    return count;
  }

  /**
   * @description 在策略时间后为启用且未删除的资产生成幂等投递。
   * @author Freddy
   * @date 2026-11-12
   * @return 新建投递数量，策略关闭或未到时间时返回零
   */
  private int scanPolicy() {
    var p = get();
    if (!Boolean.TRUE.equals(p.get("enabled"))) return 0;
    ZoneId zone = ZoneId.of((String) p.get("timezone"));
    var now = ZonedDateTime.now(zone);
    if (now.toLocalTime().isBefore(LocalTime.parse((String) p.get("scanTime")))) return 0;
    @SuppressWarnings("unchecked")
    List<Integer> thresholds = (List<Integer>) p.get("thresholds");
    String filter = Boolean.TRUE.equals(p.get("includeCa")) ? "" : " and a.certificate_kind='LEAF'";
    int created = 0;
    for (int days : thresholds) {
      var range = expiryRange(now.toLocalDate(), days, zone);
      var start = range[0];
      var end = range[1];
      var missing =
          db.queryForList(
              "select a.id,a.purpose,m.common_name,m.not_after,c.customer_name,r.email_address,r.email_key from certificate_assets a join certificate_materials m on m.id=a.material_id left join customers c on c.id=a.customer_id cross join notification_recipients r where r.policy_id=1 and a.deleted_at is null and a.archived_at is null and a.enabled=true and m.not_after>current_timestamp and m.not_after>=? and m.not_after<?"
                  + filter
                  + " and not exists(select 1 from notification_deliveries d where d.asset_id=a.id and d.not_after_snapshot=m.not_after and d.threshold_days=? and d.recipient_key=r.email_key)",
              start,
              end,
              days);
      for (var c : missing) {
        try {
          db.update(
              "insert into notification_deliveries(asset_id,policy_id,threshold_days,recipient_address,recipient_key,not_after_snapshot,cn_snapshot,customer_snapshot,purpose_snapshot,message_id) values(?,1,?,?,?,?,?,?,?,?)",
              c.get("ID"),
              days,
              c.get("EMAIL_ADDRESS"),
              c.get("EMAIL_KEY"),
              c.get("NOT_AFTER"),
              c.get("COMMON_NAME"),
              c.get("CUSTOMER_NAME"),
              c.get("PURPOSE"),
              "<" + UUID.randomUUID() + "@certmanage.local>");
          created++;
        } catch (DuplicateKeyException ignored) {
          // 多实例同时扫描仍由数据库唯一键保证幂等。
        }
      }
    }
    return created;
  }

  /**
   * @description 计算提醒目标本地日的半开区间，兼容夏令时短日与长日。
   * @author Freddy
   * @date 2026-11-16
   * @param today 策略时区的今天
   * @param days 提前提醒天数
   * @param zone 策略时区
   * @return 目标日起点及次日起点
   */
  static OffsetDateTime[] expiryRange(LocalDate today, int days, ZoneId zone) {
    var date = today.plusDays(days);
    return new OffsetDateTime[] {
      date.atStartOfDay(zone).toOffsetDateTime(),
      date.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
    };
  }

  /**
   * @description 定时扫描，独立于慢SMTP投递且后台异常时记录日志。
   * @author Freddy
   * @date 2026-11-12
   */
  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void tick() {
    try {
      scan();
    } catch (Exception e) {
      logs.append(
          "NOTIFICATION",
          "WORKER_FAILURE",
          "FAILURE",
          "notification-worker",
          null,
          null,
          null,
          null,
          "通知后台任务异常",
          Map.of());
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("通知任务暂未完成，检查数据库及配置");
    }
  }

  /**
   * @description 独立调度邮件批次，避免慢SMTP阻塞证书扫描。
   * @author Freddy
   * @date 2026-11-16
   */
  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void sendTick() {
    try {
      sendPending();
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("通知投递暂未完成，检查数据库及配置");
    }
  }

  /**
   * @description 恢复过期租约并领取启用资产的待投递任务，保留原邮件标识和重试规则。
   * @author Freddy
   * @date 2026-11-12
   */
  public synchronized void sendPending() {
    long started = System.nanoTime();
    sending = true;
    try {
      sendBatchPending();
    } finally {
      sendMillis = (System.nanoTime() - started) / 1_000_000;
      sendBatches++;
      sending = false;
    }
  }

  /**
   * @description 投递最多二十条已领取任务，同批次使用一个惰性邮件会话。
   * @author Freddy
   * @date 2026-11-16
   */
  private void sendBatchPending() {
    for (long stale :
        db.queryForList(
            "select id from notification_deliveries where delivery_status='SENDING' and lease_until<current_timestamp",
            Long.class))
      logs.append(
          "NOTIFICATION",
          "DELIVERY_INTERRUPTED",
          "INTERRUPTED",
          "notification-worker",
          null,
          null,
          "DELIVERY",
          stale,
          "通知投递租约过期，上次结果不确定",
          Map.of());
    db.update(
        "update notification_attempts set outcome='UNKNOWN',finished_at=current_timestamp where outcome='STARTED' and delivery_id in(select id from notification_deliveries where delivery_status='SENDING' and lease_until<current_timestamp)");
    db.update(
        "update notification_deliveries set delivery_status='RETRY_WAIT',next_attempt_at=current_timestamp,lease_owner=null where delivery_status='SENDING' and lease_until<current_timestamp");
    var jobs =
        db.queryForList(
            "select d.*,a.owner_type from notification_deliveries d join certificate_assets a on a.id=d.asset_id where a.deleted_at is null and a.archived_at is null and a.enabled=true and delivery_status in('PENDING','RETRY_WAIT') and (next_attempt_at is null or next_attempt_at<=current_timestamp) order by d.id fetch first 20 rows only");
    SmtpService.Batch batch = null;
    ZoneId mailZone = null;
    try {
      for (var j : jobs) {
        long id = ((Number) j.get("ID")).longValue();
        int attempt = ((Number) j.get("ATTEMPTS")).intValue() + 1;
        String lease = UUID.randomUUID().toString();
        int claimed =
            db.update(
                "update notification_deliveries set delivery_status='SENDING',attempts=?,lease_owner=?,lease_until=dateadd('MINUTE',5,current_timestamp) where id=? and delivery_status in('PENDING','RETRY_WAIT') and asset_id in(select id from certificate_assets where deleted_at is null and archived_at is null and enabled=true)",
                attempt,
                lease,
                id);
        if (claimed == 0) continue;
        if (mailZone == null) {
          mailZone = ZoneId.of((String) get().get("timezone"));
          batch = smtp.beginBatch();
        }
        boolean accepted = false;
        db.update(
            "insert into notification_attempts(delivery_id,attempt_no,started_at,outcome) values(?,?,current_timestamp,'STARTED')",
            id,
            attempt);
        try {
          logs.append(
              "NOTIFICATION",
              "DELIVERY_STARTED",
              "STARTED",
              "notification-worker",
              null,
              null,
              "DELIVERY",
              id,
              "通知投递开始",
              Map.of("attempt", attempt));
          smtp.send(
              j.get("RECIPIENT_ADDRESS").toString(),
              "证书到期提醒：" + j.get("CN_SNAPSHOT"),
              mailBody(j, Instant.now(), mailZone),
              j.get("MESSAGE_ID").toString());
          accepted = true;
          db.update(
              "update notification_deliveries set delivery_status='SENT',sent_at=current_timestamp,error_message=null,lease_until=null where id=? and lease_owner=?",
              id,
              lease);
          db.update(
              "update notification_attempts set outcome='SUCCESS',finished_at=current_timestamp where delivery_id=? and attempt_no=?",
              id,
              attempt);
          logs.appendAfterEffect(
              "NOTIFICATION",
              "DELIVERY_SUCCESS",
              "SUCCESS",
              "notification-worker",
              null,
              null,
              "DELIVERY",
              id,
              "通知投递成功",
              Map.of("attempt", attempt));
        } catch (Exception e) {
          if (accepted) {
            db.update(
                "update notification_deliveries set delivery_status='SENT',sent_at=coalesce(sent_at,current_timestamp),error_message=null,lease_until=null where id=? and lease_owner=?",
                id,
                lease);
            db.update(
                "update notification_attempts set outcome='SUCCESS',finished_at=current_timestamp where delivery_id=? and attempt_no=?",
                id,
                attempt);
            logs.appendAfterEffect(
                "NOTIFICATION",
                "DELIVERY_SUCCESS",
                "SUCCESS",
                "notification-worker",
                null,
                null,
                "DELIVERY",
                id,
                "邮件已确认接受，已恢复成功结果",
                Map.of("attempt", attempt));
            continue;
          }
          db.update(
              "update notification_deliveries set delivery_status=?,next_attempt_at=dateadd('MINUTE',15,current_timestamp),error_message='SMTP发送失败，请检查连接、TLS及凭据',lease_until=null where id=? and lease_owner=?",
              attempt >= 3 ? "FAILED" : "RETRY_WAIT",
              id,
              lease);
          db.update(
              "update notification_attempts set outcome='FAILURE',finished_at=current_timestamp,error_message='SMTP发送失败' where delivery_id=? and attempt_no=?",
              id,
              attempt);
          logs.append(
              "NOTIFICATION",
              "DELIVERY_FAILURE",
              "FAILURE",
              "notification-worker",
              null,
              null,
              "DELIVERY",
              id,
              "通知投递失败",
              Map.of("attempt", attempt));
        }
      }
    } finally {
      if (batch != null) batch.close();
    }
  }

  /**
   * @description 按策略时区生成证书到期提醒正文。
   * @author Freddy
   * @date 2026-11-12
   * @param job 含快照及归属的投递任务
   * @param now 计算剩余天数的时刻
   * @param zone 策略时区
   * @return 到期提醒正文
   */
  public static String mailBody(Map<String, Object> job, Instant now, ZoneId zone) {
    var expiry = (OffsetDateTime) job.get("NOT_AFTER_SNAPSHOT");
    long days =
        java.time.temporal.ChronoUnit.DAYS.between(
            now.atZone(zone).toLocalDate(), expiry.atZoneSameInstant(zone).toLocalDate());
    return "证书："
        + job.get("CN_SNAPSHOT")
        + "\n归属："
        + ("OWN".equals(job.get("OWNER_TYPE")) ? "自有证书" : "客户证书")
        + "\n用途："
        + job.get("PURPOSE_SNAPSHOT")
        + "\n客户："
        + Objects.toString(job.get("CUSTOMER_SNAPSHOT"), "—")
        + "\n到期时间："
        + expiry.atZoneSameInstant(zone)
        + "\n剩余天数："
        + days
        + "\n提醒节点：提前"
        + job.get("THRESHOLD_DAYS")
        + "天";
  }

  /**
   * @description 读取包含禁用资产在内的最近投递记录。
   * @author Freddy
   * @date 2026-11-12
   * @return 按标识倒序排列的最多两百条记录
   */
  public List<Map<String, Object>> records() {
    return db.queryForList(
        "select id,cn_snapshot,recipient_address,threshold_days,delivery_status,attempts,error_message,created_at,sent_at from notification_deliveries order by id desc fetch first 200 rows only");
  }

  /**
   * @description 仅将启用且未删除资产的失败投递重新入队，保留尝试次数。
   * @author Freddy
   * @date 2026-11-12
   * @param id 投递标识
   * @throws IllegalArgumentException 任务非失败状态或资产已禁用、删除时抛出
   */
  public void retry(long id) {
    require(
        db.update(
                "update notification_deliveries set delivery_status='RETRY_WAIT',attempts=attempts,next_attempt_at=current_timestamp where id=? and delivery_status='FAILED' and asset_id in(select id from certificate_assets where deleted_at is null and archived_at is null and enabled=true)",
                id)
            == 1,
        "只能重试未归档且启用证书的失败任务");
    logs.append(
        "NOTIFICATION",
        "RETRY_QUEUED",
        "INFO",
        "notification-worker",
        null,
        null,
        "DELIVERY",
        id,
        "通知已重新入队",
        Map.of());
  }

  /**
   * @description 返回固定通知指标分组。
   * @author Freddy
   * @date 2026-11-16
   * @return 通知分组名
   */
  public String performanceName() {
    return "notifications";
  }

  /**
   * @description 返回内存聚合的扫描和发送指标，不额外查询数据库。
   * @author Freddy
   * @date 2026-11-16
   * @return 耗时、次数和运行状态
   */
  public Map<String, Object> performanceSnapshot() {
    return Map.of(
        "scanMillis",
        scanMillis,
        "sendMillis",
        sendMillis,
        "scanCount",
        scanCount,
        "sendBatches",
        sendBatches,
        "scanning",
        scanning,
        "sending",
        sending);
  }
}
