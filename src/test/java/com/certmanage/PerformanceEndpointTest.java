package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @description 验证性能端点的管理员权限、计数和敏感信息隔离
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:perfendpoint;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@AutoConfigureMockMvc
class PerformanceEndpointTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired com.certmanage.common.PoolPerformanceInstrumentation poolInstrumentation;
  @Autowired com.certmanage.common.PerformanceMetrics metrics;

  /**
   * @description 只有管理员可读指标，返回禁止缓存
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void requiresAdminAndNeverCaches() throws Exception {
    mvc.perform(get("/api/v1/system/performance")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/system/performance").with(user("viewer").roles("USER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/system/performance").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.jvm.heapUsedBytes").isNumber())
        .andExpect(jsonPath("$.databasePool.maxConnections").isNumber())
        .andExpect(jsonPath("$.api.routes").isArray())
        .andExpect(jsonPath("$.queues.workflow").exists());
  }

  /**
   * @description 指标按路由模板聚合，不包含查询参数、用户输入ID或认证数据，轮询不制造审计记录
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void recordsTemplateMetricsWithoutSecretsOrSelfAudit() throws Exception {
    mvc.perform(get("/api/v1/customers?token=secret-marker").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/certificates/987654321").with(user("admin").roles("ADMIN")))
        .andExpect(status().isUnprocessableEntity());
    long before = db.queryForObject("select count(*) from execution_logs", Long.class);
    var response =
        mvc.perform(get("/api/v1/system/performance").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(response.contains("/api/v1/customers"));
    assertTrue(response.contains("/api/v1/certificates/{id}"));
    for (String secret :
        new String[] {"secret-marker", "987654321", "ciphertext", "password_hash", "master.key"})
      assertFalse(response.contains(secret), secret);
    assertEquals(before, db.queryForObject("select count(*) from execution_logs", Long.class));
  }

  /**
   * @description 真实占满隔离连接池，验证Hikari超时回调可被指标观测
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 连接或异步测试失败
   */
  @Test
  void observesActualConnectionTimeout() throws Exception {
    long before = ((Number) metrics.poolSnapshot().get("timeoutCount")).longValue();
    try (var isolated = new com.zaxxer.hikari.HikariDataSource()) {
      isolated.setJdbcUrl("jdbc:h2:mem:timeout-" + java.util.UUID.randomUUID());
      isolated.setUsername("sa");
      isolated.setPassword("");
      isolated.setMaximumPoolSize(1);
      isolated.setMinimumIdle(1);
      isolated.setConnectionTimeout(250);
      poolInstrumentation.postProcessAfterInitialization(isolated, "timeout-test");
      var probe = new JdbcTemplate(isolated);
      try (var held = isolated.getConnection()) {
        var attempt =
            java.util.concurrent.CompletableFuture.runAsync(
                () ->
                    assertThrows(
                        org.springframework.jdbc.CannotGetJdbcConnectionException.class,
                        () -> probe.queryForObject("select 1", Integer.class)));
        attempt.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(((Number) metrics.poolSnapshot().get("timeoutCount")).longValue() > before);
      }
    }
  }
}
