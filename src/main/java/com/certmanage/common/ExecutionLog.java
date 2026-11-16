package com.certmanage.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 保存可检索摘要，过滤前事件另存于加密原始详情。 */
@Service
public class ExecutionLog implements PerformanceSource {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final RawExecutionLog raw;
  private final DurableAuditOutbox<Pending> pending;
  private final org.springframework.transaction.support.TransactionTemplate retryTransaction;

  private record Pending(
      String deliveryKey,
      OffsetDateTime occurredAt,
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details,
      String trace) {}

  private static final Set<String> FIELDS =
      Set.of(
          "requestUrl",
          "requestMethod",
          "elapsedMs",
          "bytes",
          "entries",
          "filename",
          "statusCode",
          "serverAlias",
          "triggerType",
          "revision",
          "count",
          "attempt",
          "deliveryId",
          "thresholdDays",
          "errorType");

  /**
   * @description 为完成后摘要配置可靠密文待写箱，旧日志保持兼容
   * @author Freddy
   * @date 2026-11-16
   * @param db 数据库模板
   * @param json JSON编码器
   * @param raw 原始审计服务
   * @param secrets 主密钥服务
   * @param transactions 事务管理器
   */
  public ExecutionLog(
      JdbcTemplate db,
      ObjectMapper json,
      RawExecutionLog raw,
      SecretStore secrets,
      org.springframework.transaction.PlatformTransactionManager transactions) {
    this.db = db;
    this.json = json;
    this.raw = raw;
    this.pending =
        new DurableAuditOutbox<>(
            DurableAuditOutbox.directory(db, secrets, "summary"),
            json,
            secrets,
            Pending.class,
            1024L * 1024 * 1024);
    this.retryTransaction =
        new org.springframework.transaction.support.TransactionTemplate(transactions);
    this.retryTransaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * @description 完成后的事件失败仅补写审计，密文持久化不重放远端副作用
   * @author Freddy
   * @date 2026-11-16
   * @param source 来源
   * @param code 事件代码
   * @param outcome 结果
   * @param actor 执行人
   * @param run 执行ID
   * @param step 步骤
   * @param targetType 对象类型
   * @param target 对象ID
   * @param message 事件描述
   * @param details 原始详情，摘要只保留允许字段
   */
  public void appendAfterEffect(
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details) {
    details = requestDetails(details);
    String trace = raw.application(code, message, details, step);
    var event =
        new Pending(
            UUID.randomUUID().toString(),
            OffsetDateTime.now(),
            source,
            code,
            outcome,
            resolveActor(actor),
            run,
            step,
            targetType,
            target,
            message,
            safeDetails(details),
            trace);
    try {
      insert(event);
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unavailable) {
      pending.enqueue(event.deliveryKey(), event);
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("已完成操作的审计摘要已加密落盘，恢复后只补写审计");
    }
  }

  /**
   * @description 用单事务批量补写密文摘要，提交不确定时以唯一delivery_key去重
   * @author Freddy
   * @date 2026-11-16
   */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void flushPending() {
    try {
      pending.drain(
          200,
          32L * 1024 * 1024 + 28,
          events ->
              retryTransaction.executeWithoutResult(
                  status -> {
                    var arguments = events.stream().map(this::arguments).toList();
                    db.batchUpdate(INSERT, arguments);
                  }));
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unavailable) {
      // 密文保留在待写箱，下一次只补写日志，不执行任何业务。
    }
  }

  /**
   * @description 严格保存执行前摘要，完整详情仍由原始审计单独加密
   * @author Freddy
   * @date 2026-11-16
   * @param source 来源
   * @param code 事件代码
   * @param outcome 结果
   * @param actor 执行人
   * @param run 执行ID
   * @param step 步骤
   * @param targetType 对象类型
   * @param target 对象ID
   * @param message 事件描述
   * @param details 事件字段
   */
  public void append(
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details) {
    details = requestDetails(details);
    insert(
        new Pending(
            UUID.randomUUID().toString(),
            OffsetDateTime.now(),
            source,
            code,
            outcome,
            resolveActor(actor),
            run,
            step,
            targetType,
            target,
            message,
            safeDetails(details),
            raw.application(code, message, details, step)));
  }

  /**
   * @description 在请求线程捕获后端接口路径和方法，不记录查询参数，供异步重试沿用
   * @author Freddy
   * @date 2026-11-16
   * @param details 原有事件详情，允许为空
   * @return 附带当前请求信息的事件详情，无请求时保留原值
   */
  private Map<String, ?> requestDetails(Map<String, ?> details) {
    var attributes =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (!(attributes
        instanceof org.springframework.web.context.request.ServletRequestAttributes servlet))
      return details;
    var result = new LinkedHashMap<String, Object>();
    if (details != null) result.putAll(details);
    result.put("requestUrl", servlet.getRequest().getRequestURI());
    result.put("requestMethod", servlet.getRequest().getMethod());
    return result;
  }

  private String resolveActor(String actor) {
    if (actor == null || actor.endsWith("-worker")) {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      return authentication != null
              && authentication.isAuthenticated()
              && !"anonymousUser".equals(authentication.getName())
          ? authentication.getName()
          : "system";
    }
    return actor;
  }

  private static final String INSERT =
      "insert into execution_logs(delivery_key,occurred_at,source,event_code,outcome,actor_name,run_id,step_key,target_type,target_id,message,detail_json,raw_trace_id) select ?,?,?,?,?,?,?,?,?,?,?,?,? where not exists(select 1 from execution_logs where delivery_key=?)";

  /**
   * @description 仅保留允许的摘要标量字段，避免待写箱重复保存完整正文
   * @author Freddy
   * @date 2026-11-16
   * @param details 事件详情
   * @return 安全且有界的摘要
   */
  private Map<String, Object> safeDetails(Map<String, ?> details) {
    var safe = new LinkedHashMap<String, Object>();
    if (details != null)
      for (var e : details.entrySet())
        if (FIELDS.contains(e.getKey())
            && (e.getValue() instanceof Number
                || e.getValue() instanceof Boolean
                || e.getValue() instanceof String))
          safe.put(
              e.getKey(),
              e.getValue() instanceof String value
                  ? value.substring(0, Math.min(value.length(), 300))
                  : e.getValue());
    return safe;
  }

  /**
   * @description 同一事件的首次插入和重试使用同一幂等键
   * @author Freddy
   * @date 2026-11-16
   * @param event 不可变摘要
   * @return SQL参数
   */
  private Object[] arguments(Pending event) {
    try {
      return new Object[] {
        event.deliveryKey(),
        event.occurredAt(),
        event.source(),
        event.code(),
        event.outcome(),
        event.actor() == null ? "system" : event.actor(),
        event.run(),
        event.step(),
        event.targetType(),
        event.target(),
        event.message(),
        json.writeValueAsString(event.details()),
        event.trace(),
        event.deliveryKey()
      };
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("审计摘要编码失败", e);
    }
  }

  /**
   * @description 以唯一投递键保存摘要，不存在时才插入
   * @author Freddy
   * @date 2026-11-16
   * @param event 摘要
   */
  private void insert(Pending event) {
    db.update(INSERT, arguments(event));
  }

  /**
   * @description 返回摘要待写箱分组
   * @author Freddy
   * @date 2026-11-16
   * @return 固定名称
   */
  @Override
  public String performanceName() {
    return "summaryAudit";
  }

  /**
   * @description 非阻塞读取密文积压规模，不公开目录或事件内容
   * @author Freddy
   * @date 2026-11-16
   * @return 聚合计数
   */
  @Override
  public Map<String, Object> performanceSnapshot() {
    return Map.of(
        "pendingCount",
        pending.pendingCount(),
        "pendingBytes",
        pending.pendingBytes(),
        "maxPendingBytes",
        1024L * 1024 * 1024,
        "maxPendingCount",
        10000,
        "missingFiles",
        pending.missingFiles());
  }

  /**
   * @description 保留原页码分页与总数响应，使用明确字段投影
   * @author Freddy
   * @date 2026-11-16
   * @param source 来源
   * @param run 执行ID
   * @param outcome 结果
   * @param from 起始时间
   * @param to 结束时间
   * @param page 页码
   * @param size 页大小
   * @return 原分页响应
   */
  public Map<String, Object> list(
      String source, Long run, String outcome, String from, String to, int page, int size) {
    return new AuditLogQuery(db, json)
        .list(source, run, outcome, from, to, page, size, false, null, true);
  }

  /**
   * @description 按可选游标检索，不强制每页计数，保留同时间事件稳定排序
   * @author Freddy
   * @date 2026-11-16
   * @param source 来源
   * @param run 执行ID
   * @param outcome 结果
   * @param from 起始时间
   * @param to 结束时间
   * @param size 页大小
   * @param cursor 前页位置
   * @param includeTotal 是否查询总数
   * @return 游标分页响应
   */
  public Map<String, Object> cursor(
      String source,
      Long run,
      String outcome,
      String from,
      String to,
      int size,
      String cursor,
      boolean includeTotal) {
    return new AuditLogQuery(db, json)
        .list(source, run, outcome, from, to, 1, size, true, cursor, includeTotal);
  }

  /**
   * @description 查询选定日志所属步骤的全部摘要及原始事件元数据，大载荷按需读取
   * @author Freddy
   * @date 2026-11-13
   * @param id 列表条目主键
   * @return 条目、关联时间线和原始详情元数据
   */
  public Map<String, Object> detail(long id) {
    var entry = stored(id);
    String trace = (String) entry.get("RAW_TRACE_ID"), step = (String) entry.get("STEP_KEY");
    var args = new ArrayList<Object>();
    String where;
    if (entry.get("RUN_ID") != null) {
      where = "run_id=?";
      args.add(entry.get("RUN_ID"));
      if (step != null) {
        where += " and step_key=?";
        args.add(step);
      }
    } else if (trace != null) {
      where = "raw_trace_id=?";
      args.add(trace);
    } else {
      where = "id=?";
      args.add(id);
    }
    var events =
        db.query(
            "select id,occurred_at,event_code,outcome,message,step_key from execution_logs where "
                + where
                + " order by occurred_at,id",
            (r, n) -> {
              var event = new LinkedHashMap<String, Object>();
              event.put("id", r.getLong("id"));
              event.put("occurredAt", r.getObject("occurred_at", OffsetDateTime.class).toString());
              event.put("eventCode", r.getString("event_code"));
              event.put("outcome", r.getString("outcome"));
              event.put("message", r.getString("message"));
              event.put("stepKey", r.getString("step_key"));
              return event;
            },
            args.toArray());
    var original = new ArrayList<Map<String, Object>>();
    for (String related : traces(entry)) original.addAll(raw.metadata(related, step));
    original.sort(
        java.util.Comparator.comparing(
            event -> OffsetDateTime.parse(event.get("occurredAt").toString()).toInstant()));
    var visible = new LinkedHashMap<String, Object>();
    visible.put("id", id);
    visible.put("message", entry.get("MESSAGE"));
    visible.put("runId", entry.get("RUN_ID"));
    visible.put("stepKey", step);
    visible.put("source", entry.get("SOURCE"));
    try {
      visible.put("details", json.readValue(entry.get("DETAIL_JSON").toString(), Map.class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("审计日志解码失败", e);
    }
    return Map.of(
        "entry",
        visible,
        "events",
        events,
        "rawEvents",
        original,
        "hasOriginals",
        !original.isEmpty(),
        "pendingOriginals",
        raw.performanceSnapshot().get("pendingCount"));
  }

  /**
   * @description 限定在当前日志所属执行与步骤内读取原始载荷
   * @author Freddy
   * @date 2026-11-13
   * @param id 摘要主键
   * @param rawId 原始事件主键
   * @return 解密后完整载荷
   */
  public Map<String, Object> original(long id, String rawId) {
    var entry = stored(id);
    for (String trace : traces(entry)) {
      try {
        return raw.payload(trace, (String) entry.get("STEP_KEY"), rawId);
      } catch (org.springframework.web.server.ResponseStatusException absent) {
        if (absent.getStatusCode().value() != 404) throw absent;
      }
    }
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND, "原始条目不存在或不属于当前步骤");
  }

  /**
   * @description 运行级条目汇总入队与执行上下文，步骤级仍严格限定步骤
   * @author Freddy
   * @date 2026-11-13
   * @param entry 选定摘要内部字段
   * @return 允许访问的执行关联集合
   */
  private List<String> traces(Map<String, Object> entry) {
    if (entry.get("RUN_ID") == null)
      return entry.get("RAW_TRACE_ID") == null
          ? List.of()
          : List.of(entry.get("RAW_TRACE_ID").toString());
    var args = new ArrayList<Object>();
    args.add(entry.get("RUN_ID"));
    String sql =
        "select distinct raw_trace_id from execution_logs where run_id=? and raw_trace_id is not null";
    if (entry.get("STEP_KEY") != null) {
      sql += " and step_key=?";
      args.add(entry.get("STEP_KEY"));
    }
    return db.queryForList(sql, String.class, args.toArray());
  }

  /**
   * @description 定位摘要记录，不向调用方暴露不存在记录的数据
   * @author Freddy
   * @date 2026-11-13
   * @param id 摘要主键
   * @return 内部数据库字段
   */
  private Map<String, Object> stored(long id) {
    var rows = db.queryForList("select * from execution_logs where id=?", id);
    if (rows.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "日志不存在");
    return rows.getFirst();
  }
}
