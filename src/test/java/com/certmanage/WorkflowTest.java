package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.*;
import com.certmanage.server.*;
import com.certmanage.sync.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 验证同步工作流执行与证书归档删除引用保护
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class WorkflowTest {
  @Autowired com.certmanage.common.ExecutionLog logs;
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired ServerService servers;
  @Autowired CertificateService certs;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;

  /**
   * @description 构造证书库制作和 HTTP 上传任务
   * @author Freddy
   * @date 2026-11-12
   * @param asset 证书资产 ID
   * @param server 服务器 ID
   * @return 任务请求
   */
  Map<String, Object> task(long asset, long server) {
    return new HashMap<>(
        Map.of(
            "name",
            "Workflow Test",
            "state",
            "READY",
            "steps",
            List.of(
                Map.of(
                    "key",
                    "make",
                    "name",
                    "制作",
                    "type",
                    "KEYSTORE",
                    "config",
                    Map.of(
                        "format",
                        "PKCS12",
                        "filename",
                        "identity.p12",
                        "password",
                        "storepass123",
                        "entries",
                        List.of(Map.of("assetId", asset, "alias", "identity", "role", "PRIVATE")))),
                Map.of(
                    "key",
                    "upload",
                    "name",
                    "上传",
                    "type",
                    "HTTP",
                    "config",
                    Map.of(
                        "serverId",
                        server,
                        "sourceStep",
                        "make",
                        "path",
                        "/import",
                        "method",
                        "PUT",
                        "bodyMode",
                        "BINARY",
                        "filename",
                        "identity.p12")))));
  }

  /**
   * @description 验证快照执行、上传及归档资产活跃引用保护
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception HTTP 测试服务器初始化失败时抛出
   */
  @Test
  void makesStoreUploadsSnapshotAndProtectsReferences() throws Exception {
    var body = new AtomicReference<byte[]>();
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/import",
        exchange -> {
          body.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    http.start();
    try {
      tx.executeWithoutResult(
          s -> {
            long root =
                ((Number)
                        certs
                            .issue(Map.of("kind", "ROOT", "cn", "Sync Root", "days", 3650), "admin")
                            .get("id"))
                    .longValue();
            long mid =
                ((Number)
                        certs
                            .issue(
                                Map.of(
                                    "kind",
                                    "INTERMEDIATE",
                                    "cn",
                                    "Sync Middle",
                                    "days",
                                    1825,
                                    "issuerId",
                                    root),
                                "admin")
                            .get("id"))
                    .longValue();
            long leaf =
                ((Number)
                        certs
                            .issue(
                                Map.of(
                                    "kind",
                                    "LEAF",
                                    "cn",
                                    "Sync Leaf",
                                    "days",
                                    365,
                                    "issuerId",
                                    mid),
                                "admin")
                            .get("id"))
                    .longValue();
            var server =
                servers.save(
                    null,
                    Map.of(
                        "alias",
                        "Local Workflow",
                        "type",
                        "HTTP",
                        "config",
                        Map.of("baseUrl", "http://127.0.0.1:" + http.getAddress().getPort())));
            long serverId = ((Number) server.get("id")).longValue();
            var input = task(leaf, serverId);
            var saved = workflows.save(null, input, "admin");
            long taskId = ((Number) saved.get("id")).longValue();
            assertFalse(saved.toString().contains("storepass123"));
            long run = workflows.enqueue(taskId, "workflow-once", "admin");
            assertEquals(run, workflows.enqueue(taskId, "workflow-once", "admin"));
            assertThrows(
                IllegalArgumentException.class,
                () -> workflows.enqueue(taskId, "workflow-twice", "admin"));
            certs.archive(leaf, "admin");
            assertThrows(IllegalArgumentException.class, () -> certs.deleteArchived(leaf, "admin"));
            certs.restore(leaf, "admin");
            var edited = task(leaf, serverId);
            edited.put("revision", saved.get("revision"));
            edited.put("state", "DRAFT");
            edited.put("steps", List.of());
            workflows.save(taskId, edited, "admin");
            certs.archive(leaf, "admin");
            assertThrows(IllegalArgumentException.class, () -> certs.deleteArchived(leaf, "admin"));
            certs.restore(leaf, "admin");
            servers.save(
                serverId,
                Map.of(
                    "alias",
                    "Local Workflow",
                    "type",
                    "HTTP",
                    "revision",
                    server.get("revision"),
                    "config",
                    Map.of("baseUrl", "http://127.0.0.1:1")));
            runner.executeRun(run);
            assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
            var events = logs.list("SYNC", run, null, null, null, 1, 100).toString();
            assertTrue(events.contains("RUN_QUEUED"));
            assertTrue(events.contains("STEP_SUCCESS"));
            assertTrue(events.contains("RUN_SUCCESS"));
            assertFalse(events.contains("storepass123"));
            assertNotNull(body.get());
            try {
              var store = KeyStore.getInstance("PKCS12");
              store.load(new ByteArrayInputStream(body.get()), "storepass123".toCharArray());
              assertEquals(3, store.getCertificateChain("identity").length);
              assertTrue(store.isKeyEntry("identity"));
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            assertEquals(2, ((List<?>) workflows.runDetail(run).get("steps")).size());
            certs.archive(leaf, "admin");
            certs.deleteArchived(leaf, "admin");
            s.setRollbackOnly();
          });
    } finally {
      http.stop(0);
    }
  }

  /**
   * @description 验证输入顺序约束和上传失败后停止后续步骤
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void rejectsForwardInputsAndStopsAfterFailedUpload() {
    tx.executeWithoutResult(
        s -> {
          long root =
              ((Number)
                      certs
                          .issue(
                              Map.of("kind", "ROOT", "cn", "Fail Sync Root", "days", 3650), "admin")
                          .get("id"))
                  .longValue();
          var server =
              servers.save(
                  null,
                  Map.of(
                      "alias",
                      "Fail Workflow",
                      "type",
                      "HTTP",
                      "config",
                      Map.of(
                          "baseUrl", "http://127.0.0.1:1", "connectTimeout", 1, "readTimeout", 1)));
          var input = task(root, ((Number) server.get("id")).longValue());
          var original = (List<?>) input.get("steps");
          input.put(
              "steps",
              List.of(
                  original.get(0),
                  original.get(1),
                  Map.of(
                      "key",
                      "later",
                      "name",
                      "Should Skip",
                      "type",
                      "HTTP",
                      "config",
                      Map.of(
                          "serverId",
                          ((Number) server.get("id")).longValue(),
                          "path",
                          "/",
                          "bodyMode",
                          "JSON",
                          "body",
                          "{}"))));
          var saved = workflows.save(null, input, "admin");
          long taskId = ((Number) saved.get("id")).longValue();
          long run = workflows.enqueue(taskId, "fail-run", "admin");
          runner.executeRun(run);
          assertEquals("FAILED", workflows.runDetail(run).get("status"));
          var events = logs.list("SYNC", run, null, null, null, 1, 100).toString();
          assertTrue(events.contains("STEP_FAILURE"));
          assertTrue(events.contains("STEP_SKIPPED"));
          assertTrue(events.contains("RUN_FAILURE"));
          assertEquals(
              "SKIPPED",
              ((Map<?, ?>) ((List<?>) workflows.runDetail(run).get("steps")).get(2)).get("status"));
          assertFalse(workflows.runDetail(run).toString().contains("storepass123"));
          var bad = new HashMap<>(input);
          bad.put("name", "Bad order");
          var steps = (List<?>) input.get("steps");
          bad.put("steps", List.of(steps.get(1), steps.get(0)));
          assertThrows(IllegalArgumentException.class, () -> workflows.save(null, bad, "admin"));
          s.setRollbackOnly();
        });
  }
}
