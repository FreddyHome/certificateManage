package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.ExecutionLog;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * @description 验证完成后摘要在故障、重启和提交结果不确定时可靠且幂等补写
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:summary-recovery;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SummaryAuditRecoveryTest {
  @Autowired ExecutionLog log;
  @Autowired ApplicationContext context;
  @MockitoSpyBean JdbcTemplate db;

  /**
   * @description 数据库失败后创建新服务实例，仍能恢复密文摘要而不重放业务
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void recoversAfterServiceRestart() {
    var mode = new AtomicInteger(1);
    installFailure(mode);
    assertDoesNotThrow(
        () ->
            log.appendAfterEffect(
                "RECOVERY",
                "DONE",
                "SUCCESS",
                "admin",
                null,
                null,
                null,
                null,
                "persist-after-restart",
                Map.of("password", "must-not-store", "bytes", 123)));
    mode.set(0);
    var restarted = context.getAutowireCapableBeanFactory().createBean(ExecutionLog.class);
    restarted.flushPending();
    restarted.flushPending();
    assertEquals(
        1L,
        db.queryForObject(
            "select count(*) from execution_logs where message='persist-after-restart'",
            Long.class));
    assertFalse(
        restarted
            .list("RECOVERY", null, null, null, null, 1, 50)
            .toString()
            .contains("must-not-store"));
  }

  /**
   * @description 模拟数据库已提交但客户端收到错误，同一幂等键补写不能产生第二条
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void unknownCommitDoesNotDuplicateSummary() {
    var mode = new AtomicInteger(2);
    installFailure(mode);
    log.appendAfterEffect(
        "RECOVERY", "DONE", "SUCCESS", "admin", null, null, null, null, "unknown-commit", Map.of());
    mode.set(0);
    log.flushPending();
    log.flushPending();
    assertEquals(
        1L,
        db.queryForObject(
            "select count(*) from execution_logs where message='unknown-commit'", Long.class));
  }

  /**
   * @description 只模拟摘要插入故障，不影响原始日志或查询
   * @author Freddy
   * @date 2026-11-16
   * @param mode 1为提交前失败，2为提交后失败，0正常
   */
  private void installFailure(AtomicInteger mode) {
    doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.startsWith("insert into execution_logs(") && mode.get() == 1)
                throw new DataAccessResourceFailureException("isolated outage");
              Object result = invocation.callRealMethod();
              if (sql.startsWith("insert into execution_logs(") && mode.getAndSet(0) == 2)
                throw new DataAccessResourceFailureException("unknown commit");
              return result;
            })
        .when(db)
        .update(anyString(), any(Object[].class));
  }
}
