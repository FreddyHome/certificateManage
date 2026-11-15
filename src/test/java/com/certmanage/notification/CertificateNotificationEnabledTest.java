package com.certmanage.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.certmanage.certificate.CertificateService;
import com.certmanage.common.ExecutionLog;
import com.certmanage.smtp.SmtpService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 使用独立内存库验证禁用资产的通知暂停与重新启用恢复规则。
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:notification-enabled;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key"
    })
@Transactional
class CertificateNotificationEnabledTest {
  @Autowired JdbcTemplate db;
  @Autowired CertificateService certificates;
  @Autowired ExecutionLog logs;

  /**
   * @description 禁用期间不生成投递，重新启用恢复扫描并保持唯一投递规则。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void disabledAssetsDoNotGenerateDeliveriesAndReenabledScanRemainsIdempotent() {
    long asset = asset();
    db.update(
        "update notification_policy set enabled=true,include_ca=true,scan_time='00:00:00',timezone_id='UTC' where id=1");
    db.update("delete from notification_thresholds");
    db.update("insert into notification_thresholds values(1,3650)");
    db.update("delete from notification_recipients");
    db.update(
        "insert into notification_recipients(policy_id,email_address,email_key) values(1,'enabled@example.test','enabled@example.test')");
    var worker = new NotificationService(db, mock(SmtpService.class), logs);
    db.update("update certificate_assets set enabled=false where id=?", asset);
    worker.scan();
    assertEquals(0, deliveryCount(asset));
    db.update("update certificate_assets set enabled=true where id=?", asset);
    worker.scan();
    worker.scan();
    assertEquals(1, deliveryCount(asset));
  }

  /**
   * @description 禁用保留待投递及原邮件标识，重新启用后仅发送一次。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void disabledPendingDeliveryWaitsForReenable() {
    long asset = asset();
    long delivery = pending(asset);
    var transport = mock(SmtpService.class);
    var worker = new NotificationService(db, transport, logs);
    db.update("update certificate_assets set enabled=false where id=?", asset);
    worker.sendPending();
    verifyNoInteractions(transport);
    assertEquals("PENDING", status(delivery));
    assertEquals(
        0,
        db.queryForObject(
            "select attempts from notification_deliveries where id=?", Integer.class, delivery));
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from notification_attempts where delivery_id=?",
            Integer.class,
            delivery));
    db.update("update certificate_assets set enabled=true where id=?", asset);
    worker.sendPending();
    worker.sendPending();
    verify(transport)
        .send(
            eq("enabled@example.test"),
            anyString(),
            anyString(),
            eq("<enabled-test@example.test>"));
    assertEquals("SENT", status(delivery));
  }

  /**
   * @description 禁用资产不可手动重试，重新启用沿用原失败任务和尝试次数。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void disabledFailedDeliveryCannotRetryUntilReenabled() {
    long asset = asset();
    long delivery = pending(asset);
    db.update(
        "update notification_deliveries set delivery_status='FAILED',attempts=3 where id=?",
        delivery);
    db.update("update certificate_assets set enabled=false where id=?", asset);
    var worker = new NotificationService(db, mock(SmtpService.class), logs);
    assertThrows(IllegalArgumentException.class, () -> worker.retry(delivery));
    assertEquals("FAILED", status(delivery));
    assertEquals(1, deliveryCount(asset));
    db.update("update certificate_assets set enabled=true where id=?", asset);
    worker.retry(delivery);
    assertEquals("RETRY_WAIT", status(delivery));
    assertEquals(
        3,
        db.queryForObject(
            "select attempts from notification_deliveries where id=?", Integer.class, delivery));
  }

  /**
   * @description 任务查询后资产被禁用时，领取语句再次检查资产状态以阻止新发送。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void disablingBetweenSelectionAndClaimPreventsSend() {
    long asset = asset();
    long delivery = pending(asset);
    var transport = mock(SmtpService.class);
    var racingDb = spy(db);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              db.update("update certificate_assets set enabled=false where id=?", asset);
              return result;
            })
        .when(racingDb)
        .queryForList(startsWith("select d.*,a.owner_type"));
    new NotificationService(racingDb, transport, logs).sendPending();
    verifyNoInteractions(transport);
    assertEquals("PENDING", status(delivery));
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from notification_attempts where delivery_id=?",
            Integer.class,
            delivery));
  }

  /**
   * @description 创建用于通知测试的自有根证书。
   * @author Freddy
   * @date 2026-11-12
   * @return 新资产标识
   */
  private long asset() {
    return ((Number)
            certificates
                .issue(
                    Map.of("kind", "ROOT", "cn", "Enabled Notification Root", "days", 3650),
                    "admin")
                .get("id"))
        .longValue();
  }

  /**
   * @description 创建带固定邮件标识的待投递任务。
   * @author Freddy
   * @date 2026-11-12
   * @param asset 关联资产标识
   * @return 新投递标识
   */
  private long pending(long asset) {
    db.update(
        "insert into notification_deliveries(asset_id,policy_id,threshold_days,recipient_address,recipient_key,not_after_snapshot,cn_snapshot,purpose_snapshot,message_id) values(?,1,30,'enabled@example.test','enabled@example.test',dateadd('DAY',29,current_timestamp),'Enabled Notification Root','GENERAL','<enabled-test@example.test>')",
        asset);
    return db.queryForObject(
        "select id from notification_deliveries where asset_id=?", Long.class, asset);
  }

  /**
   * @description 查询资产保留的投递数量。
   * @author Freddy
   * @date 2026-11-12
   * @param asset 资产标识
   * @return 投递记录数
   */
  private int deliveryCount(long asset) {
    return db.queryForObject(
        "select count(*) from notification_deliveries where asset_id=?", Integer.class, asset);
  }

  /**
   * @description 查询投递当前状态。
   * @author Freddy
   * @date 2026-11-12
   * @param delivery 投递标识
   * @return 投递状态
   */
  private String status(long delivery) {
    return db.queryForObject(
        "select delivery_status from notification_deliveries where id=?", String.class, delivery);
  }

  /**
   * @description 已归档但enabled仍为true的证书暂停通知，恢复后继续原投递
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void archivedAssetCannotSendOrRetryButRestoreResumesDelivery() {
    long asset = asset();
    long delivery = pending(asset);
    var transport = mock(SmtpService.class);
    var worker = new NotificationService(db, transport, logs);
    db.update("update certificate_assets set archived_at=current_timestamp where id=?", asset);
    worker.sendPending();
    verifyNoInteractions(transport);
    assertEquals("PENDING", status(delivery));
    db.update("update notification_deliveries set delivery_status='FAILED' where id=?", delivery);
    assertThrows(IllegalArgumentException.class, () -> worker.retry(delivery));
    db.update("update certificate_assets set archived_at=null where id=?", asset);
    worker.retry(delivery);
    worker.sendPending();
    assertEquals("SENT", status(delivery));
    verify(transport, times(1)).send(anyString(), anyString(), anyString(), anyString());
  }

  /**
   * @description 归档资产即使启用且命中提醒日也不会生成新投递
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void archivedEnabledAssetIsNotScanned() {
    long asset = asset();
    db.update(
        "update notification_policy set enabled=true,include_ca=true,scan_time='00:00:00',timezone_id='UTC' where id=1");
    db.update("delete from notification_thresholds");
    db.update("insert into notification_thresholds values(1,3650)");
    db.update("delete from notification_recipients");
    db.update(
        "insert into notification_recipients(policy_id,email_address,email_key) values(1,'archive@example.test','archive@example.test')");
    db.update("update certificate_assets set archived_at=current_timestamp where id=?", asset);
    new NotificationService(db, mock(SmtpService.class), logs).scan();
    assertEquals(0, deliveryCount(asset));
  }
}
