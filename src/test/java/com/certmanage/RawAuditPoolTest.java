package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.common.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 单连接池中业务事务内的原始审计不能等待第二个连接或丢失回滚前数据
 * @author Freddy
 * @date 2026-11-13
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:rawpool;DB_CLOSE_DELAY=-1",
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.connection-timeout=1500",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class RawAuditPoolTest {
  @Autowired RawExecutionLog raw;
  @Autowired ExecutionLog logs;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager manager;
  @Autowired javax.sql.DataSource dataSource;

  /**
   * @description 父事务占用唯一连接时采集立即返回，回滚后仍能查询原始详情
   * @author Freddy
   * @date 2026-11-13
   */
  @Test
  void parentTransactionDoesNotWaitForAnotherConnection() throws Exception {
    var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
    pool.setMinimumIdle(0);
    pool.setMaximumPoolSize(1);
    pool.getHikariPoolMXBean().softEvictConnections();
    try (var scope = raw.begin()) {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                db.queryForObject("select 1", Integer.class);
                long start = System.nanoTime();
                RawExecutionLog.event("POOL_TEST", "原始连接池事件", Map.of("message", "before rollback"));
                assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < 500);
                var sink = RawExecutionLog.sink();
                var callback =
                    java.util.concurrent.CompletableFuture.runAsync(
                        () ->
                            sink.record(
                                "ASYNC_POOL_TEST",
                                "异步会话事件",
                                Map.of("message", "callback while parent holds connection")));
                assertDoesNotThrow(
                    () -> callback.get(500, java.util.concurrent.TimeUnit.MILLISECONDS));
                status.setRollbackOnly();
              });
      logs.append(
          "SERVER", "POOL_TEST", "FAILURE", "admin", null, null, null, null, "事务回滚摘要", Map.of());
    }
    long id = db.queryForObject("select max(id) from execution_logs", Long.class);
    assertTrue(logs.detail(id).get("rawEvents").toString().contains("POOL_TEST"));
    assertTrue(logs.detail(id).get("rawEvents").toString().contains("ASYNC_POOL_TEST"));
  }
}
