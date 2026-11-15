package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.*;
import com.certmanage.common.ExecutionLog;
import com.certmanage.customer.*;
import com.certmanage.server.ServerService;
import com.certmanage.sync.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 使用本地真实HTTP接口验证固定组件报文、冻结快照和业务失败日志
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class FixedComponentsWorkflowTest {
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired ServerService servers;
  @Autowired CertificateService certs;
  @Autowired CertificateCrypto crypto;
  @Autowired CustomerService customers;
  @Autowired JdbcTemplate db;
  @Autowired ExecutionLog logs;
  @Autowired ObjectMapper json;

  /**
   * @description 验证根到终端DER编码、最新有效证书、客户标识快照与响应消息日志，业务失败停止后续步骤
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception 证书构造或本地HTTP接口初始化失败时抛出
   */
  @Test
  @Transactional
  void freezesPartnerPayloadAndLogsBothBusinessOutcomes() throws Exception {
    var requestBody = new AtomicReference<String>();
    var reloadQuery = new AtomicReference<String>();
    var reloadMethod = new AtomicReference<String>();
    var updateMethod = new AtomicReference<String>();
    var failing = new AtomicBoolean(false);
    var afterCalls = new AtomicInteger();
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/wm/is/cert/reload",
        exchange -> {
          reloadQuery.set(exchange.getRequestURI().getRawQuery());
          reloadMethod.set(exchange.getRequestMethod());
          byte[] response =
              "{\"message\":\"reload-message-marker\",\"isError\":\"false\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    http.createContext(
        "/wm/tn/partner/cert/update",
        exchange -> {
          updateMethod.set(exchange.getRequestMethod());
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              (failing.get()
                      ? "{\"code\":\"7\",\"message\":\"partner-failure-marker\"}"
                      : "{\"code\":\"0\",\"message\":\"partner-success-marker\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    http.createContext(
        "/after",
        exchange -> {
          afterCalls.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    http.start();
    try {
      String customerName = "Fixed partner " + UUID.randomUUID();
      customers.add(
          new CustomerRequest(
              customerName, "", "987654321-${fileBase64}-${certificateBase64}", "DUNS"),
          "admin");
      long customer = customers.findOrCreateForImport(customerName);
      var root = crypto.issue("ROOT", "Partner root", 3650, null, null, List.of(), "GENERAL");
      var mid =
          crypto.issue(
              "INTERMEDIATE",
              "Partner middle",
              1825,
              root.cert(),
              root.key(),
              List.of(),
              "GENERAL");
      var oldLeaf =
          crypto.issue("LEAF", "Partner old", 365, mid.cert(), mid.key(), List.of(), "AS2");
      var newLeaf =
          crypto.issue("LEAF", "Partner new", 730, mid.cert(), mid.key(), List.of(), "AS2");
      certs.saveImport(
          new CertificateCrypto.Parsed(List.of(root.cert(), mid.cert(), oldLeaf.cert()), null),
          "CUSTOMER",
          customerName,
          "AS2",
          "admin");
      long selected =
          ((Number)
                  certs
                      .saveImport(
                          new CertificateCrypto.Parsed(
                              List.of(root.cert(), mid.cert(), newLeaf.cert()), null),
                          "CUSTOMER",
                          customerName,
                          "AS2",
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
                              "Fixed server " + UUID.randomUUID(),
                              "type",
                              "HTTP",
                              "config",
                              Map.of("baseUrl", "http://127.0.0.1:" + http.getAddress().getPort())))
                      .get("id"))
              .longValue();
      var steps =
          List.of(
              Map.of(
                  "key",
                  "reload",
                  "name",
                  "Reload",
                  "type",
                  "HTTP",
                  "config",
                  Map.of(
                      "serverId",
                      server,
                      "httpComponent",
                      "RELOAD_KEYSTORE",
                      "keyStoreName",
                      "test store",
                      "type",
                      "JKS")),
              Map.of(
                  "key",
                  "partner",
                  "name",
                  "Partner",
                  "type",
                  "HTTP",
                  "config",
                  Map.of(
                      "serverId",
                      server,
                      "httpComponent",
                      "UPDATE_TN_PARTNER",
                      "customerId",
                      customer,
                      "purpose",
                      "AS2")),
              Map.of(
                  "key",
                  "after",
                  "name",
                  "After",
                  "type",
                  "HTTP",
                  "config",
                  Map.of(
                      "serverId", server, "path", "/after", "method", "GET", "bodyMode", "NONE")));
      long task =
          ((Number)
                  workflows
                      .save(
                          null,
                          Map.of("name", "Fixed flow", "state", "READY", "steps", steps),
                          "admin")
                      .get("id"))
              .longValue();
      long run = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
      assertEquals(
          3,
          db.queryForObject(
              "select count(*) from sync_run_certificates where run_id=? and source_asset_id=?",
              Integer.class,
              run,
              selected));
      db.update("update customers set external_id='changed-after-enqueue' where id=?", customer);
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      assertEquals("GET", reloadMethod.get());
      assertTrue(reloadQuery.get().contains("type=JKS"));
      assertTrue(
          reloadQuery.get().contains("keyStoreName=test+store")
              || reloadQuery.get().contains("keyStoreName=test%20store"));
      assertEquals("POST", updateMethod.get());
      var payload = json.readTree(requestBody.get());
      assertEquals("987654321-${fileBase64}-${certificateBase64}", payload.path("id").asText());
      assertEquals("DUNS", payload.path("idType").asText());
      var chain = List.of(root.cert(), mid.cert(), newLeaf.cert());
      assertEquals(3, payload.path("certChain").size());
      for (int i = 0; i < chain.size(); i++) {
        assertEquals(i + 1, payload.path("certChain").get(i).path("index").asInt());
        assertArrayEquals(
            chain.get(i).getEncoded(),
            Base64.getDecoder()
                .decode(payload.path("certChain").get(i).path("certificateBase64").asText()));
      }
      String messages = logs.list("SYNC", run, null, null, null, 1, 100).toString();
      assertTrue(messages.contains("reload-message-marker"));
      assertTrue(messages.contains("partner-success-marker"));
      certs.setEnabled(selected, false, "admin");
      failing.set(true);
      long failed = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
      runner.executeRun(failed);
      assertEquals("FAILED", workflows.runDetail(failed).get("status"));
      var fallbackPayload = json.readTree(requestBody.get());
      assertArrayEquals(
          oldLeaf.cert().getEncoded(),
          Base64.getDecoder()
              .decode(fallbackPayload.path("certChain").get(2).path("certificateBase64").asText()));
      assertTrue(
          logs.list("SYNC", failed, null, null, null, 1, 100)
              .toString()
              .contains("partner-failure-marker"));
      certs.setEnabled(selected, true, "admin");
      certs.archive(selected, "admin");
      long archivedRun = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
      runner.executeRun(archivedRun);
      var archivedPayload = json.readTree(requestBody.get());
      assertArrayEquals(
          oldLeaf.cert().getEncoded(),
          Base64.getDecoder()
              .decode(archivedPayload.path("certChain").get(2).path("certificateBase64").asText()));
      assertEquals(1, afterCalls.get());
    } finally {
      http.stop(0);
    }
  }
}
