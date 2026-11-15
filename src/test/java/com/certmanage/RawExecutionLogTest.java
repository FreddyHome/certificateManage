package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.certmanage.common.ExecutionLog;
import com.certmanage.common.RawExecutionLog;
import com.certmanage.server.RemoteTransport;
import com.certmanage.server.ServerService;
import com.certmanage.sync.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @description 验证原始报文加密、步骤关联、异常堆栈与详情访问边界
 * @author Freddy
 * @date 2026-11-13
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:rawaudit;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@AutoConfigureMockMvc
class RawExecutionLogTest {
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired ServerService servers;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ExecutionLog logs;
  @Autowired RawExecutionLog raw;
  @Autowired RemoteTransport transport;
  @TempDir Path folder;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  /**
   * @description 原始记录不应随产生异常的业务事务一起回滚
   * @author Freddy
   * @date 2026-11-13
   */
  @Test
  void rawDetailsSurviveBusinessTransactionRollback() {
    try (var scope = raw.begin()) {
      var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
      tx.setPropagationBehavior(
          org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      tx.executeWithoutResult(
          status -> {
            RawExecutionLog.event(
                "ROLLBACK_RAW", "回滚前原始事件", Map.of("message", "raw-before-rollback"));
            status.setRollbackOnly();
          });
      logs.append(
          "SERVER", "ROLLED_BACK", "FAILURE", "admin", null, null, null, null, "事务失败摘要", Map.of());
    }
    long event = db.queryForObject("select max(id) from execution_logs", Long.class);
    assertTrue(logs.detail(event).get("rawEvents").toString().contains("ROLLBACK_RAW"));
  }

  /**
   * @description 现有16MiB上传上限产生的Base64必须能完整读取，不能被JSON字符串上限阻断
   * @author Freddy
   * @date 2026-11-13
   */
  @Test
  void originalSixteenMiBPayloadCanBeReadWithoutTruncation() {
    String body = Base64.getEncoder().encodeToString(new byte[16 * 1024 * 1024]);
    try (var scope = raw.begin()) {
      RawExecutionLog.event("LARGE_BODY", "原始大文件", Map.of("bodyBase64", body));
      logs.append(
          "SERVER", "LARGE_BODY", "SUCCESS", "admin", null, null, null, null, "大文件摘要", Map.of());
    }
    long event = db.queryForObject("select max(id) from execution_logs", Long.class);
    var detail = logs.detail(event);
    @SuppressWarnings("unchecked")
    var events = (List<Map<String, Object>>) detail.get("rawEvents");
    String rawId =
        events.stream()
            .filter(e -> e.get("kind").equals("LARGE_BODY"))
            .findFirst()
            .orElseThrow()
            .get("id")
            .toString();
    @SuppressWarnings("unchecked")
    var payload = (Map<String, Object>) logs.original(event, rawId).get("payload");
    assertEquals(body, payload.get("bodyBase64"));
  }

  /**
   * @description 保存原始异常、cause及suppressed堆栈，并采集真实应用日志
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 详情查询失败
   */
  @Test
  void retainsOriginalExceptionCauseSuppressedAndApplicationLogs() throws Exception {
    try (var scope = raw.begin()) {
      var failure =
          new IllegalArgumentException("原始异常raw-error", new java.io.IOException("raw-cause"));
      failure.addSuppressed(new IllegalStateException("raw-suppressed"));
      RawExecutionLog.exception(failure);
      org.slf4j.LoggerFactory.getLogger(getClass()).info("raw-application-log-marker");
      logs.append(
          "SERVER",
          "TEST_FAILURE",
          "FAILURE",
          "admin",
          null,
          null,
          "SERVER",
          null,
          "失败摘要",
          Map.of());
    }
    long event = db.queryForObject("select max(id) from execution_logs", Long.class);
    String details = allOriginals(event).toString();
    assertTrue(details.contains("raw-error"));
    assertTrue(details.contains("Caused by:"));
    assertTrue(details.contains("raw-cause"));
    assertTrue(details.contains("Suppressed:"));
    assertTrue(details.contains("raw-suppressed"));
    assertTrue(details.contains("raw-application-log-marker"));
  }

  /**
   * @description 超时仍保存实际已收到的字节，标记响应不完整并保留底层异常
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 测试服务器失败
   */
  @Test
  void timeoutPreservesPartialResponseAndOriginalStack() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    http.createContext(
        "/",
        e -> {
          e.sendResponseHeaders(200, 100);
          e.getResponseBody().write("first".getBytes(StandardCharsets.UTF_8));
          e.getResponseBody().flush();
          try {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          e.close();
        });
    http.start();
    try {
      long server =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              "timeout raw",
                              "type",
                              "HTTP",
                              "config",
                              Map.of(
                                  "baseUrl",
                                  "http://127.0.0.1:" + http.getAddress().getPort(),
                                  "readTimeout",
                                  1,
                                  "connectTimeout",
                                  1)))
                      .get("id"))
              .longValue();
      try (var scope = raw.begin()) {
        assertThrows(
            Exception.class,
            () ->
                transport.execute(
                    server,
                    ((Number) servers.snapshot(server).get("configId")).longValue(),
                    Map.of("path", "/", "method", "GET", "bodyMode", "NONE"),
                    null,
                    ""));
        logs.append(
            "SERVER", "TIMEOUT", "FAILURE", "admin", null, null, "SERVER", server, "超时摘要",
            Map.of());
      }
      long event = db.queryForObject("select max(id) from execution_logs", Long.class);
      var originals = allOriginals(event);
      var response = originals.stream().filter(p -> p.has("statusCode")).findFirst().orElseThrow();
      assertEquals(
          "first",
          new String(
              Base64.getDecoder().decode(response.path("bodyBase64").asText()),
              StandardCharsets.UTF_8));
      assertFalse(response.path("complete").asBoolean());
      assertTrue(
          originals.stream().anyMatch(p -> p.path("stackTrace").asText().contains("IOException")));
    } finally {
      release.countDown();
      http.stop(0);
    }
  }

  /**
   * @description SFTP上传与读取的原始文件和异步SSH会话事件关联到发起的操作
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception SSH服务器或文件处理失败
   */
  @Test
  void retainsSftpFilesAndSessionEvents() throws Exception {
    var ssh = SshServer.setUpDefaultServer();
    ssh.setPort(0);
    ssh.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(folder.resolve("hostkey")));
    ssh.setPasswordAuthenticator((u, p, s) -> u.equals("user") && p.equals("secret"));
    ssh.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    ssh.setFileSystemFactory(new VirtualFileSystemFactory(folder));
    ssh.start();
    byte[] file = "raw-sftp-content\u0000\u0001".getBytes(StandardCharsets.UTF_8);
    try {
      long server =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              "raw sftp",
                              "type",
                              "SFTP",
                              "config",
                              Map.of(
                                  "host",
                                  "127.0.0.1",
                                  "port",
                                  ssh.getPort(),
                                  "username",
                                  "user",
                                  "password",
                                  "secret",
                                  "authType",
                                  "PASSWORD")))
                      .get("id"))
              .longValue();
      long version = ((Number) servers.snapshot(server).get("configId")).longValue();
      try (var scope = raw.begin()) {
        transport.execute(server, version, Map.of("remoteDirectory", "/"), file, "original.bin");
        transport.readFile(
            server, version, Map.of("operation", "READ", "sourcePath", "/original.bin"), 1024);
        logs.append(
            "SERVER",
            "SFTP_COMPLETE",
            "SUCCESS",
            "admin",
            null,
            null,
            "SERVER",
            server,
            "SFTP摘要",
            Map.of());
      }
      long event = db.queryForObject("select max(id) from execution_logs", Long.class);
      var detail = getJson("/api/v1/execution-logs/" + event);
      assertTrue(detail.path("rawEvents").toString().contains("SSH_SESSION"));
      var originals = allOriginals(event);
      assertTrue(
          originals.stream()
                  .filter(p -> p.has("bodyBase64"))
                  .filter(
                      p ->
                          p.path("bodyBase64")
                              .asText()
                              .equals(Base64.getEncoder().encodeToString(file)))
                  .count()
              >= 2);
    } finally {
      ssh.stop(true);
    }
  }

  /**
   * @description 按元数据逐条读取原始载荷
   * @author Freddy
   * @date 2026-11-13
   * @param event 摘要主键
   * @return 完整原始载荷列表
   * @throws Exception 详情请求失败
   */
  private List<JsonNode> allOriginals(long event) throws Exception {
    var result = new ArrayList<JsonNode>();
    for (var entry : getJson("/api/v1/execution-logs/" + event).path("rawEvents"))
      result.add(
          getJson("/api/v1/execution-logs/" + event + "/raw/" + entry.path("id").asText())
              .path("payload"));
    return result;
  }

  /**
   * @description 用真实HTTP确认头、正文和响应原样保存但不进入列表或明文数据库列
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 本地服务器或断言失败
   */
  @Test
  void storesOriginalHttpMessagesEncryptedAndIsolatesSteps() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String response = "{\n  \"original\":\"raw-response-marker\"\n}";
    http.createContext(
        "/",
        e -> {
          e.getRequestBody().readAllBytes();
          e.getResponseHeaders().add("X-Original", "raw-response-header");
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(200, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    http.start();
    try {
      long server =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              "raw http",
                              "type",
                              "HTTP",
                              "config",
                              Map.of(
                                  "baseUrl",
                                  "http://127.0.0.1:" + http.getAddress().getPort(),
                                  "authType",
                                  "BEARER",
                                  "token",
                                  "raw-auth-marker")))
                      .get("id"))
              .longValue();
      var steps = new ArrayList<Map<String, Object>>();
      for (String key : List.of("first", "second"))
        steps.add(
            Map.of(
                "key",
                key,
                "name",
                key,
                "type",
                "HTTP",
                "config",
                Map.of(
                    "serverId",
                    server,
                    "path",
                    "/" + key,
                    "method",
                    "POST",
                    "bodyMode",
                    "JSON",
                    "headers",
                    Map.of("X-Original", "raw-request-header"),
                    "body",
                    "{\"value\":\"raw-request-" + key + "\"}")));
      long task =
          ((Number)
                  workflows
                      .save(
                          null,
                          Map.of("name", "raw audit", "state", "READY", "steps", steps),
                          "admin")
                      .get("id"))
              .longValue();
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      long event =
          db.queryForObject(
              "select id from execution_logs where run_id=? and step_key='first' and event_code='STEP_SUCCESS'",
              Long.class,
              run);
      mvc.perform(get("/api/v1/execution-logs/" + event)).andExpect(status().isUnauthorized());
      JsonNode detail = getJson("/api/v1/execution-logs/" + event);
      long queued =
          db.queryForObject(
              "select id from execution_logs where run_id=? and event_code='RUN_QUEUED'",
              Long.class,
              run);
      assertTrue(
          getJson("/api/v1/execution-logs/" + queued)
              .path("rawEvents")
              .toString()
              .contains("HTTP_REQUEST"));
      assertFalse(detail.path("rawEvents").isEmpty());
      String originals = "";
      for (var raw : detail.path("rawEvents"))
        originals +=
            getJson("/api/v1/execution-logs/" + event + "/raw/" + raw.path("id").asText())
                .toString();
      assertTrue(originals.contains("raw-auth-marker"));
      assertTrue(originals.contains("raw-response-header"));
      var bodies = new ArrayList<String>();
      for (var raw : detail.path("rawEvents")) {
        var payload =
            getJson("/api/v1/execution-logs/" + event + "/raw/" + raw.path("id").asText())
                .path("payload");
        if (payload.has("bodyBase64"))
          bodies.add(
              new String(
                  Base64.getDecoder().decode(payload.path("bodyBase64").asText()),
                  StandardCharsets.UTF_8));
      }
      assertTrue(bodies.contains(response));
      assertTrue(bodies.contains("{\"value\":\"raw-request-first\"}"));
      assertFalse(bodies.stream().anyMatch(body -> body.contains("raw-request-second")));
      assertFalse(
          logs.list("SYNC", run, null, null, null, 1, 200).toString().contains("raw-auth-marker"));
      for (byte[] ciphertext :
          db.query("select payload_ciphertext from execution_log_raw", (r, n) -> r.getBytes(1)))
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("raw-request-first"));
      long second =
          db.queryForObject(
              "select id from execution_logs where run_id=? and step_key='second' and event_code='STEP_SUCCESS'",
              Long.class,
              run);
      String firstRaw = detail.path("rawEvents").get(0).path("id").asText();
      mvc.perform(
              get("/api/v1/execution-logs/" + second + "/raw/" + firstRaw)
                  .with(user("admin").roles("ADMIN")))
          .andExpect(status().isNotFound());
    } finally {
      http.stop(0);
    }
  }

  /**
   * @description 历史日志保留详情入口且不伪造原始数据
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 详情读取失败
   */
  @Test
  void legacyLogHasSummaryAndExplicitlyMissingOriginals() throws Exception {
    db.update(
        "insert into execution_logs(source,event_code,outcome,actor_name,message,detail_json) values('SYNC','LEGACY','INFO','admin','old log','{}')");
    long id = db.queryForObject("select max(id) from execution_logs", Long.class);
    var detail = getJson("/api/v1/execution-logs/" + id);
    assertTrue(detail.path("rawEvents").isEmpty());
    assertFalse(detail.path("hasOriginals").asBoolean());
  }

  /**
   * @description 读取授权后的详情JSON
   * @author Freddy
   * @date 2026-11-13
   * @param path 详情路径
   * @return JSON响应
   * @throws Exception 请求失败
   */
  private JsonNode getJson(String path) throws Exception {
    return json.readTree(
        mvc.perform(get(path).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }
}
