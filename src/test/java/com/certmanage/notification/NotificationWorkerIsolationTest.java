package com.certmanage.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.ExecutionLog;
import com.certmanage.smtp.SmtpService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 验证阻塞的邮件投递不会占用扫描锁。
 * @author Freddy
 * @date 2026-11-16
 */
class NotificationWorkerIsolationTest {
  /**
   * @description 当SMTP尚未返回时，策略扫描仍可独立完成。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 并发测试超时或执行失败时抛出
   */
  @Test
  void slowMailDoesNotBlockScan() throws Exception {
    var db = mock(JdbcTemplate.class);
    var smtp = mock(SmtpService.class);
    when(db.queryForMap(anyString()))
        .thenReturn(
            Map.of(
                "ENABLED",
                false,
                "TIMEZONE_ID",
                "UTC",
                "SCAN_TIME",
                "00:00",
                "WARNING_DAYS",
                30,
                "INCLUDE_CA",
                true));
    var job = new HashMap<String, Object>();
    job.put("ID", 1L);
    job.put("ATTEMPTS", 0);
    job.put("RECIPIENT_ADDRESS", "test@example.test");
    job.put("CN_SNAPSHOT", "test");
    job.put("MESSAGE_ID", "<test@test>");
    job.put("NOT_AFTER_SNAPSHOT", OffsetDateTime.now().plusDays(30));
    job.put("OWNER_TYPE", "OWN");
    when(db.queryForList(startsWith("select d.*,a.owner_type"))).thenReturn(List.of(job));
    when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(smtp)
        .send(anyString(), anyString(), anyString(), anyString());
    var worker = new NotificationService(db, smtp, mock(ExecutionLog.class));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var sending = pool.submit(worker::sendPending);
      try {
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertEquals(0, pool.submit(worker::scan).get(1, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      sending.get(3, TimeUnit.SECONDS);
    }
  }
}
