package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.certmanage.certificate.*;
import com.certmanage.server.ServerService;
import com.certmanage.sync.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证两类证书的启用禁用字段、恢复操作、参数校验和审计记录
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key"
    })
@AutoConfigureMockMvc
@Transactional
class CertificateEnabledTest {
  @Autowired CertificateService certificates;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired ServerService servers;

  /**
   * @description 自有与客户证书均默认启用，禁用后保留可查询内容并可重新启用
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception HTTP契约验证失败时抛出
   */
  @Test
  void togglesBothOwnersAndRecordsAudit() throws Exception {
    long root =
        ((Number)
                certificates
                    .issue(Map.of("kind", "ROOT", "cn", "Enable root", "days", 3650), "admin")
                    .get("id"))
            .longValue();
    long mid =
        ((Number)
                certificates
                    .issue(
                        Map.of(
                            "kind",
                            "INTERMEDIATE",
                            "cn",
                            "Enable middle",
                            "days",
                            1825,
                            "issuerId",
                            root),
                        "admin")
                    .get("id"))
            .longValue();
    long leaf =
        ((Number)
                certificates
                    .issue(
                        Map.of("kind", "LEAF", "cn", "Enable leaf", "days", 365, "issuerId", mid),
                        "admin")
                    .get("id"))
            .longValue();
    long customer =
        ((Number)
                certificates
                    .saveImport(
                        new CertificateCrypto.Parsed(certificates.chainCertificates(leaf), null),
                        "CUSTOMER",
                        "Enable customer",
                        "GENERAL",
                        "admin")
                    .get("id"))
            .longValue();
    for (long id : new long[] {leaf, customer}) {
      assertEquals(true, certificates.detail(id).get("enabled"));
      mvc.perform(
              patch("/api/v1/certificates/" + id + "/enabled")
                  .with(user("admin").roles("ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"enabled\":false}"))
          .andExpect(status().isOk());
      assertEquals(false, certificates.detail(id).get("enabled"));
      if (id == customer) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                servers.save(
                    null,
                    Map.of(
                        "alias",
                        "Reject disabled trust " + UUID.randomUUID(),
                        "type",
                        "HTTP",
                        "config",
                        Map.of(
                            "baseUrl", "http://127.0.0.1:1", "trustAssetIds", List.of(customer)))));
      }
      assertFalse(certificates.chainCertificates(id).isEmpty());
      mvc.perform(
              patch("/api/v1/certificates/" + id + "/enabled")
                  .with(user("admin").roles("ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"enabled\":true}"))
          .andExpect(status().isOk());
      assertEquals(true, certificates.detail(id).get("enabled"));
    }
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from audit_events where action_code='CERTIFICATE_DISABLE'",
            Integer.class));
    mvc.perform(
            patch("/api/v1/certificates/" + leaf + "/enabled")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"enabled\":null}"))
        .andExpect(status().isUnprocessableEntity());
    assertEquals(true, certificates.detail(leaf).get("enabled"));
  }

  /**
   * @description 禁用CA不再作为签发者，但仍允许管理查询和私钥导出所需读取
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void disabledCaCannotSignAndCanBeReenabled() {
    long root =
        ((Number)
                certificates
                    .issue(Map.of("kind", "ROOT", "cn", "Disabled signer", "days", 3650), "admin")
                    .get("id"))
            .longValue();
    certificates.setEnabled(root, false, "admin");
    assertTrue(
        certificates.issuers("INTERMEDIATE").stream().noneMatch(row -> row.get("id").equals(root)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            certificates.issue(
                Map.of(
                    "kind", "INTERMEDIATE", "cn", "Rejected middle", "days", 365, "issuerId", root),
                "admin"));
    assertNotNull(certificates.key(root));
    certificates.setEnabled(root, true, "admin");
    assertTrue(
        certificates.issuers("INTERMEDIATE").stream().anyMatch(row -> row.get("id").equals(root)));
  }

  /**
   * @description 排队后禁用输入证书会阻止执行，恢复后入队资格恢复
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void queuedRunDoesNotBypassDisable() {
    long root =
        ((Number)
                certificates
                    .issue(
                        Map.of("kind", "ROOT", "cn", "Disabled queued input", "days", 3650),
                        "admin")
                    .get("id"))
            .longValue();
    long server =
        ((Number)
                servers
                    .save(
                        null,
                        Map.of(
                            "alias",
                            "Disabled queued server " + UUID.randomUUID(),
                            "type",
                            "HTTP",
                            "config",
                            Map.of("baseUrl", "http://127.0.0.1:1")))
                    .get("id"))
            .longValue();
    var steps =
        List.of(
            Map.of(
                "key",
                "make",
                "name",
                "make",
                "type",
                "TRUSTSTORE",
                "config",
                Map.of(
                    "password",
                    "testpass123",
                    "entries",
                    List.of(Map.of("assetId", root, "alias", "trust")))),
            Map.of(
                "key",
                "call",
                "name",
                "call",
                "type",
                "HTTP",
                "config",
                Map.of("serverId", server, "method", "GET", "bodyMode", "NONE", "path", "/")));
    long task =
        ((Number)
                workflows
                    .save(
                        null,
                        Map.of("name", "Disable queued", "state", "READY", "steps", steps),
                        "admin")
                    .get("id"))
            .longValue();
    long run = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
    certificates.setEnabled(root, false, "admin");
    runner.executeRun(run);
    assertEquals("FAILED", workflows.runDetail(run).get("status"));
    var detailSteps = (List<?>) workflows.runDetail(run).get("steps");
    assertEquals("SKIPPED", ((Map<?, ?>) detailSteps.get(1)).get("status"));
    assertThrows(
        IllegalArgumentException.class,
        () -> workflows.enqueue(task, UUID.randomUUID().toString(), "admin"));
    certificates.setEnabled(root, true, "admin");
    long archivedRun = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
    certificates.archive(root, "admin");
    runner.executeRun(archivedRun);
    assertEquals("FAILED", workflows.runDetail(archivedRun).get("status"));
    assertThrows(
        IllegalArgumentException.class,
        () -> workflows.enqueue(task, UUID.randomUUID().toString(), "admin"));
    certificates.restore(root, "admin");
    assertTrue(workflows.enqueue(task, UUID.randomUUID().toString(), "admin") > 0);
  }
}
