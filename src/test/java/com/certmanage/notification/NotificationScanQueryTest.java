package com.certmanage.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.certmanage.certificate.CertificateService;
import com.certmanage.common.ExecutionLog;
import com.certmanage.smtp.SmtpService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证通知扫描仅查询日期区间内缺失投递，并支持当天新增资产与收件人。
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:p2-notification;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key"
    })
@Transactional
class NotificationScanQueryTest {
  @Autowired JdbcTemplate db;
  @Autowired CertificateService certificates;
  @Autowired ExecutionLog logs;

  /**
   * @description 重复扫描不读取已投递组合，追加收件人和资产仍可增量生成。
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void queriesOnlyMissingDeliveriesWithinDateBounds() {
    db.update(
        "update notification_policy set enabled=true,include_ca=true,scan_time='00:00:00',timezone_id='America/New_York' where id=1");
    db.update("delete from notification_thresholds");
    db.update("insert into notification_thresholds values(1,3650)");
    db.update("delete from notification_recipients");
    db.update(
        "insert into notification_recipients(policy_id,email_address,email_key) values(1,'one@example.test','one@example.test')");
    certificates.issue(Map.of("kind", "ROOT", "cn", "First P2 Root", "days", 3650), "admin");
    var tracked = spy(db);
    var worker = new NotificationService(tracked, mock(SmtpService.class), logs);
    assertEquals(1, worker.scan());
    assertEquals(0, worker.scan());
    db.update(
        "insert into notification_recipients(policy_id,email_address,email_key) values(1,'two@example.test','two@example.test')");
    assertEquals(1, worker.scan());
    certificates.issue(Map.of("kind", "ROOT", "cn", "Second P2 Root", "days", 3650), "admin");
    assertEquals(2, worker.scan());
    verify(tracked, never())
        .queryForList(
            startsWith("select a.id,a.purpose,m.common_name,m.not_after,c.customer_name from"));
    verify(tracked, atLeastOnce()).queryForList(contains("not exists"), any(Object[].class));
    verify(tracked, times(4))
        .update(startsWith("insert into notification_deliveries"), any(Object[].class));
  }

  /**
   * @description 按策略日期计算区间，夏令时切换日分别包含23或25小时。
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void dateRangeFollowsDstAndNonIntegerOffsetZones() {
    var zone = ZoneId.of("America/New_York");
    var spring = NotificationService.expiryRange(LocalDate.of(2027, 3, 13), 1, zone);
    assertEquals(23, Duration.between(spring[0], spring[1]).toHours());
    var fall = NotificationService.expiryRange(LocalDate.of(2027, 11, 6), 1, zone);
    assertEquals(25, Duration.between(fall[0], fall[1]).toHours());
    var kathmandu =
        NotificationService.expiryRange(LocalDate.of(2027, 1, 1), 0, ZoneId.of("Asia/Kathmandu"));
    assertEquals(ZoneOffset.ofHoursMinutes(5, 45), kathmandu[0].getOffset());
    assertEquals(LocalTime.MIDNIGHT, kathmandu[0].toLocalTime());
  }

  /**
   * @description 每轮最多投递二十条，部分失败保留邮件标识重试且不影响后续邮件。
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void preservesTwentyJobLimitAndPartialFailureRetry() {
    long asset =
        ((Number)
                certificates
                    .issue(Map.of("kind", "ROOT", "cn", "Batch Root", "days", 3650), "admin")
                    .get("id"))
            .longValue();
    for (int i = 0; i < 21; i++)
      db.update(
          "insert into notification_deliveries(asset_id,policy_id,threshold_days,recipient_address,recipient_key,not_after_snapshot,cn_snapshot,purpose_snapshot,message_id) values(?,1,30,?,?,dateadd('DAY',30,current_timestamp),'Batch Root','GENERAL',?)",
          asset,
          "user" + i + "@example.test",
          "user" + i + "@example.test",
          "<batch-" + i + "@test>");
    var smtp = mock(SmtpService.class);
    doThrow(new IllegalArgumentException("stub SMTP failure"))
        .doNothing()
        .when(smtp)
        .send(anyString(), anyString(), anyString(), anyString());
    var worker = new NotificationService(db, smtp, logs);
    worker.sendPending();
    assertEquals(
        19,
        db.queryForObject(
            "select count(*) from notification_deliveries where asset_id=? and delivery_status='SENT'",
            Integer.class,
            asset));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from notification_deliveries where asset_id=? and delivery_status='PENDING'",
            Integer.class,
            asset));
    verify(smtp, times(20)).send(anyString(), anyString(), anyString(), anyString());
    db.update(
        "update notification_deliveries set next_attempt_at=current_timestamp where asset_id=? and delivery_status='RETRY_WAIT'",
        asset);
    worker.sendPending();
    verify(smtp, times(2)).send(anyString(), anyString(), anyString(), eq("<batch-0@test>"));
    assertEquals(
        21,
        db.queryForObject(
            "select count(*) from notification_deliveries where asset_id=? and delivery_status='SENT'",
            Integer.class,
            asset));
  }
}
