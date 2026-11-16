package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.*;
import com.certmanage.customer.*;
import com.certmanage.server.ServerService;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 用真实HTTP和证书库验证业务组件报文、原库保留与失败阻断
 * @author Freddy
 * @date 2026-11-13
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@Transactional
class BusinessComponentsWorkflowTest {
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired CertificateService certs;
  @Autowired CertificateCrypto crypto;
  @Autowired CustomerService customers;
  @Autowired ServerService servers;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @TempDir Path folder;

  @Test
  void repeatedImportIsIdempotentAndExistingAliasConflictNeverOverwrites() throws Exception {
    try (var f = new Fixture("JKS")) {
      long task = save(f.config("IS_TRUSTSTORE"));
      for (int i = 0; i < 2; i++) {
        long run = workflows.enqueue(task, null, "admin");
        runner.executeRun(run);
        assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
        byte[] output = Base64.getDecoder().decode(f.updated.get().path("content").asText());
        var store = KeyStore.getInstance("JKS");
        store.load(new ByteArrayInputStream(output), "short".toCharArray());
        assertEquals(3, store.size());
        f.sourceBytes.set(output);
      }
      var conflict = KeyStore.getInstance("JKS");
      conflict.load(new ByteArrayInputStream(f.sourceBytes.get()), "short".toCharArray());
      String alias =
          Collections.list(conflict.aliases()).stream()
              .filter(a -> a.startsWith("business-root-"))
              .findFirst()
              .orElseThrow();
      conflict.setCertificateEntry(alias, f.leaf.cert());
      var out = new ByteArrayOutputStream();
      conflict.store(out, "short".toCharArray());
      f.sourceBytes.set(out.toByteArray());
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("FAILED", workflows.runDetail(run).get("status"));
      assertEquals(2, f.updateCalls.get());
    }
  }

  /**
   * @description 验证自动私钥库按叶子CN选择最新有效链，入队快照固定且下次入队重新解析
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 证书生成或证书库读取失败
   */
  @Test
  void automaticKeyStoreReselectsSameCnAtEnqueueAndFreezesRun() throws Exception {
    try (var f = new Fixture("PKCS12")) {
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      long task = save(config);
      var newer =
          crypto.issue(
              "LEAF", "Business Leaf", 180, f.root.cert(), f.root.key(), List.of(), "GENERAL");
      long replacement =
          ((Number)
                  certs
                      .saveImport(
                          new CertificateCrypto.Parsed(
                              List.of(f.root.cert(), newer.cert()), newer.key()),
                          "OWN",
                          null,
                          "GENERAL",
                          "admin")
                      .get("id"))
              .longValue();
      long first = workflows.enqueue(task, null, "admin");
      var newest =
          crypto.issue(
              "LEAF", "Business Leaf", 200, f.root.cert(), f.root.key(), List.of(), "GENERAL");
      certs.saveImport(
          new CertificateCrypto.Parsed(List.of(f.root.cert(), newest.cert()), newest.key()),
          "OWN",
          null,
          "GENERAL",
          "admin");
      var unrelated =
          crypto.issue(
              "LEAF", "Other Business", 250, f.root.cert(), f.root.key(), List.of(), "GENERAL");
      certs.saveImport(
          new CertificateCrypto.Parsed(List.of(f.root.cert(), unrelated.cert()), unrelated.key()),
          "OWN",
          null,
          "GENERAL",
          "admin");
      runner.executeRun(first);
      assertEquals("SUCCESS", workflows.runDetail(first).get("status"));
      var store = KeyStore.getInstance("PKCS12");
      store.load(
          new ByteArrayInputStream(
              Base64.getDecoder().decode(f.updated.get().path("content").asText())),
          "short".toCharArray());
      assertEquals(newer.cert(), store.getCertificate("Business Leaf"));
      assertNotNull(store.getKey("Business Leaf", "short".toCharArray()));
      assertEquals(2, store.getCertificateChain("Business Leaf").length);
      db.update(
          "update certificate_assets set archived_at=current_timestamp,deleted_at=current_timestamp where id=?",
          f.own);
      long second = workflows.enqueue(task, null, "admin");
      runner.executeRun(second);
      assertEquals("SUCCESS", workflows.runDetail(second).get("status"));
      store.load(
          new ByteArrayInputStream(
              Base64.getDecoder().decode(f.updated.get().path("content").asText())),
          "short".toCharArray());
      assertEquals(newest.cert(), store.getCertificate("Business Leaf"));
      assertNotEquals(f.own, replacement);
    }
  }

  /**
   * @description 验证自动模式跳过不可用候选及失效基准，指定模式保持固定证书
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 证书生成或任务校验失败
   */
  @Test
  void automaticKeyStoreSkipsUnavailableCandidatesAndManualStaysFixed() throws Exception {
    try (var f = new Fixture("JKS")) {
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      config.put("selectionMode", "MANUAL");
      long fixed = save(config);
      long valid = 0;
      for (int days : List.of(120, 140, 160, 180, 200, 220)) {
        var leaf =
            crypto.issue(
                "LEAF", "Business Leaf", days, f.root.cert(), f.root.key(), List.of(), "GENERAL");
        long id =
            ((Number)
                    certs
                        .saveImport(
                            new CertificateCrypto.Parsed(
                                List.of(f.root.cert(), leaf.cert()),
                                days == 140 ? null : leaf.key()),
                            "OWN",
                            null,
                            "GENERAL",
                            "admin")
                        .get("id"))
                .longValue();
        if (days == 120) valid = id;
        if (days == 160) db.update("update certificate_assets set enabled=false where id=?", id);
        if (days == 180)
          db.update("update certificate_assets set archived_at=current_timestamp where id=?", id);
        if (days == 200)
          db.update("update certificate_assets set deleted_at=current_timestamp where id=?", id);
        if (days == 220) {
          var builder =
              new JcaX509v3CertificateBuilder(
                  new X500Name("CN=Business Root"),
                  BigInteger.valueOf(220),
                  Date.from(Instant.now().minusSeconds(86400)),
                  Date.from(Instant.now().minusSeconds(1)),
                  new X500Name("CN=Business Leaf"),
                  leaf.cert().getPublicKey());
          var expired =
              new JcaX509CertificateConverter()
                  .getCertificate(
                      builder.build(
                          new JcaContentSignerBuilder("SHA256withRSA").build(f.root.key())));
          db.update(
              "update certificate_materials set certificate_der=? where id=(select material_id from certificate_assets where id=?)",
              expired.getEncoded(),
              id);
        }
      }
      long run = workflows.enqueue(fixed, null, "admin");
      var snapshot =
          json.readTree(
              db.queryForObject(
                  "select snapshot_json from sync_runs where id=?", String.class, run));
      assertEquals(
          f.own, snapshot.path("steps").get(0).path("config").path("resolvedAssetId").asLong());
      runner.executeRun(run);
      config.put("selectionMode", "AUTO");
      long automatic = save(config);
      db.update("update certificate_assets set enabled=false where id=?", f.own);
      long autoRun = workflows.enqueue(automatic, null, "admin");
      var autoSnapshot =
          json.readTree(
              db.queryForObject(
                  "select snapshot_json from sync_runs where id=?", String.class, autoRun));
      assertEquals(
          valid, autoSnapshot.path("steps").get(0).path("config").path("resolvedAssetId").asLong());
      runner.executeRun(autoRun);
      db.update("update certificate_assets set enabled=false where id=?", valid);
      assertThrows(
          IllegalArgumentException.class, () -> workflows.enqueue(automatic, null, "admin"));
    }
  }

  @Test
  void cumulativeArtifactsOverTwentyFiveMiBStopBeforeThirdWrite() throws Exception {
    try (var f = new Fixture("JKS")) {
      f.sourceBytes.set(Arrays.copyOf(f.sourceBytes.get(), 9 * 1024 * 1024));
      var steps = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < 3; i++)
        steps.add(
            Map.of(
                "key",
                "store" + i,
                "name",
                "store" + i,
                "type",
                "HTTP",
                "config",
                f.config("IS_TRUSTSTORE")));
      long task =
          ((Number)
                  workflows
                      .save(
                          null,
                          Map.of("name", "size limit", "state", "READY", "steps", steps),
                          "admin")
                      .get("id"))
              .longValue();
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("FAILED", workflows.runDetail(run).get("status"));
      assertEquals(2, f.updateCalls.get());
    }
  }

  @Test
  void updateUrlMustBelongToSelectedServerAndKeyStorePathIsRequired() throws Exception {
    try (var f = new Fixture("JKS")) {
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      config.put("storePath", "");
      assertThrows(IllegalArgumentException.class, () -> save(config));
      config.put("storePath", "/target/store.jks");
      config.put("path", "http://unrelated.example/update");
      assertThrows(IllegalArgumentException.class, () -> save(config));
      config.put("path", "http://127.0.0.1:" + f.http.getAddress().getPort() + "/update");
      long task = save(config);
      assertEquals(
          "/update",
          SyncData.map(SyncData.maps(workflows.detail(task).get("steps")).getFirst().get("config"))
              .get("path"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pkcs12KeyStorePreservesFullChainAndRejectsDifferentKeyPassword(boolean different)
      throws Exception {
    try (var f = new Fixture("PKCS12")) {
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      if (different) config.put("keyPassword", "another");
      long run = workflows.enqueue(save(config), null, "admin");
      runner.executeRun(run);
      assertEquals(different ? "FAILED" : "SUCCESS", workflows.runDetail(run).get("status"));
      if (different) assertNull(f.updated.get());
      else {
        var store = KeyStore.getInstance("PKCS12");
        store.load(
            new ByteArrayInputStream(
                Base64.getDecoder().decode(f.updated.get().path("content").asText())),
            "short".toCharArray());
        String alias = store.aliases().nextElement();
        assertEquals(2, store.getCertificateChain(alias).length);
        assertNotNull(store.getKey(alias, "short".toCharArray()));
      }
    }
  }

  @Test
  void refusesPrivateSourceAsTruststore() throws Exception {
    try (var f = new Fixture("JKS")) {
      var store = KeyStore.getInstance("JKS");
      store.load(null, "short".toCharArray());
      store.setKeyEntry(
          "private",
          f.leaf.key(),
          "short".toCharArray(),
          new java.security.cert.Certificate[] {f.leaf.cert(), f.root.cert()});
      var out = new ByteArrayOutputStream();
      store.store(out, "short".toCharArray());
      f.sourceBytes.set(out.toByteArray());
      long run = workflows.enqueue(save(f.config("IS_TRUSTSTORE")), null, "admin");
      runner.executeRun(run);
      assertEquals("FAILED", workflows.runDetail(run).get("status"));
      assertNull(f.updated.get());
    }
  }

  @Test
  void editingBlankPasswordsKeepsEncryptedSecrets() throws Exception {
    try (var f = new Fixture("JKS")) {
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      config.put("keyPassword", "different");
      long task = save(config);
      var detail = workflows.detail(task);
      var step = SyncData.maps(detail.get("steps")).getFirst();
      var edited = SyncData.map(step.get("config"));
      assertEquals(true, edited.get("passwordConfigured"));
      assertEquals(true, edited.get("keyPasswordConfigured"));
      edited.put("password", "");
      edited.put("keyPassword", "");
      step.put("config", edited);
      detail.put("steps", List.of(step));
      workflows.save(task, detail, "admin");
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      var store = KeyStore.getInstance("JKS");
      store.load(
          new ByteArrayInputStream(
              Base64.getDecoder().decode(f.updated.get().path("content").asText())),
          "short".toCharArray());
      assertNotNull(store.getKey(store.aliases().nextElement(), "different".toCharArray()));
    }
  }

  @Test
  void automaticallyUsesNewestChainAndFreezesItAtEnqueue() throws Exception {
    try (var f = new Fixture("JKS")) {
      String customer = certs.detail(f.customerAsset).get("customer").toString();
      var newer =
          crypto.issue("LEAF", "newer", 180, f.root.cert(), f.root.key(), List.of(), "GENERAL");
      certs.saveImport(
          new CertificateCrypto.Parsed(List.of(f.root.cert(), newer.cert()), null),
          "CUSTOMER",
          customer,
          "GENERAL",
          "admin");
      var config = f.config("TN_CERTIFICATE");
      config.put("path", "/tn");
      long task = save(config);
      long run = workflows.enqueue(task, null, "admin");
      var newest =
          crypto.issue("LEAF", "newest", 270, f.root.cert(), f.root.key(), List.of(), "GENERAL");
      certs.saveImport(
          new CertificateCrypto.Parsed(List.of(f.root.cert(), newest.cert()), null),
          "CUSTOMER",
          customer,
          "GENERAL",
          "admin");
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      assertArrayEquals(
          newer.cert().getEncoded(),
          Base64.getDecoder()
              .decode(f.updated.get().path("certChain").get(1).path("certificateBase64").asText()));
      long next = workflows.enqueue(task, null, "admin");
      runner.executeRun(next);
      assertArrayEquals(
          newest.cert().getEncoded(),
          Base64.getDecoder()
              .decode(f.updated.get().path("certChain").get(1).path("certificateBase64").asText()));
    }
  }

  @Test
  void businessFailureSkipsFollowingComponent() throws Exception {
    try (var f = new Fixture("JKS")) {
      f.updateCode.set("1");
      long task =
          ((Number)
                  workflows
                      .save(
                          null,
                          Map.of(
                              "name",
                              "failure flow",
                              "state",
                              "READY",
                              "steps",
                              List.of(
                                  Map.of(
                                      "key",
                                      "first",
                                      "name",
                                      "first",
                                      "type",
                                      "HTTP",
                                      "config",
                                      f.config("IS_TRUSTSTORE")),
                                  Map.of(
                                      "key",
                                      "next",
                                      "name",
                                      "next",
                                      "type",
                                      "HTTP",
                                      "config",
                                      f.config("IS_TRUSTSTORE")))),
                          "admin")
                      .get("id"))
              .longValue();
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("FAILED", workflows.runDetail(run).get("status"));
      assertEquals(1, f.updateCalls.get());
      assertEquals(
          1,
          db.queryForObject(
              "select count(*) from sync_run_steps where run_id=? and status='SKIPPED'",
              Integer.class,
              run));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 11})
  void httpReadSupportsLargeFilesAndRejectsMoreThanTenMiB(int megabytes) throws Exception {
    try (var f = new Fixture("JKS")) {
      f.sourceBytes.set(Arrays.copyOf(f.sourceBytes.get(), megabytes * 1024 * 1024));
      long run = workflows.enqueue(save(f.config("IS_TRUSTSTORE")), null, "admin");
      runner.executeRun(run);
      assertEquals(megabytes > 10 ? "FAILED" : "SUCCESS", workflows.runDetail(run).get("status"));
      if (megabytes > 10) assertNull(f.updated.get());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 400})
  void updateResponseCannotEchoEncodedPrivateStoreIntoLogs(int status) throws Exception {
    try (var f = new Fixture("JKS")) {
      f.echoResponse.set(true);
      f.updateStatus.set(status);
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      long run = workflows.enqueue(save(config), null, "admin");
      runner.executeRun(run);
      String marker = f.echoMarker.get();
      assertNotNull(marker);
      String result = json.writeValueAsString(workflows.runDetail(run));
      assertEquals(status == 200 ? "SUCCESS" : "FAILED", workflows.runDetail(run).get("status"));
      assertFalse(result.contains(marker));
      assertEquals(
          0,
          db.queryForObject(
              "select count(*) from execution_logs where run_id=? and (message like ? or detail_json like ?)",
              Integer.class,
              run,
              "%" + marker + "%",
              "%" + marker + "%"));
    }
  }

  @Test
  void removesOnlyExpiredEntriesWhenEnabledAndRecordsProcessingCounts() throws Exception {
    try (var f = new Fixture("JKS")) {
      var builder =
          new JcaX509v3CertificateBuilder(
              new X500Name("CN=Expired"),
              BigInteger.ONE,
              Date.from(Instant.now().minusSeconds(86400)),
              Date.from(Instant.now().minusSeconds(1)),
              new X500Name("CN=Expired"),
              f.root.cert().getPublicKey());
      var expired =
          new JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(f.root.key())));
      var original = KeyStore.getInstance("JKS");
      original.load(new ByteArrayInputStream(f.sourceBytes.get()), "short".toCharArray());
      original.setCertificateEntry("expired", expired);
      var out = new ByteArrayOutputStream();
      original.store(out, "short".toCharArray());
      f.sourceBytes.set(out.toByteArray());
      for (boolean delete : List.of(false, true)) {
        var config = f.config("IS_TRUSTSTORE");
        config.put("deleteExpired", delete);
        long run = workflows.enqueue(save(config), null, "admin");
        runner.executeRun(run);
        assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
        var store = KeyStore.getInstance("JKS");
        store.load(
            new ByteArrayInputStream(
                Base64.getDecoder().decode(f.updated.get().path("content").asText())),
            "short".toCharArray());
        assertEquals(!delete, store.containsAlias("expired"));
        assertTrue(store.containsAlias("original"));
        assertTrue(
            json.writeValueAsString(workflows.runDetail(run)).contains("删除" + (delete ? 1 : 0)));
      }
    }
  }

  @Test
  void manualTrustNodesMustBelongToChainAndOnlySelectedNodesAreImported() throws Exception {
    try (var f = new Fixture("JKS")) {
      long rootId =
          ((Number) certs.chain(f.customerAsset).getFirst().get("materialId")).longValue();
      var config = f.config("IS_TRUSTSTORE");
      config.put("selectionMode", "MANUAL");
      config.put("certificateId", f.customerAsset);
      config.put("materialIds", List.of(rootId));
      long run = workflows.enqueue(save(config), null, "admin");
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      var store = KeyStore.getInstance("JKS");
      store.load(
          new ByteArrayInputStream(
              Base64.getDecoder().decode(f.updated.get().path("content").asText())),
          "short".toCharArray());
      assertEquals(2, store.size());
      config.put("materialIds", List.of(-1));
      assertThrows(IllegalArgumentException.class, () -> save(config));
      config.put("materialIds", List.of());
      assertThrows(IllegalArgumentException.class, () -> save(config));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"code\":\"1\",\"message\":\"failed\"}",
        "not json",
        "{\"code\":\"0\",\"path\":\"/x\",\"content\":\"!\"}",
        "{\"path\":\"/x\",\"content\":\"YQ==\"}"
      })
  void invalidReadResponseNeverWrites(String response) throws Exception {
    try (var f = new Fixture("JKS")) {
      f.readResponse.set(response);
      long run = workflows.enqueue(save(f.config("IS_TRUSTSTORE")), null, "admin");
      runner.executeRun(run);
      assertEquals("FAILED", workflows.runDetail(run).get("status"));
      assertNull(f.updated.get());
    }
  }

  @Test
  void sourceAndTargetReferencesAndConnectionVersionsAreBothFrozen() throws Exception {
    try (var f = new Fixture("JKS")) {
      long source =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              "Separate source",
                              "type",
                              "HTTP",
                              "config",
                              Map.of(
                                  "baseUrl", "http://127.0.0.1:" + f.http.getAddress().getPort())))
                      .get("id"))
              .longValue();
      var config = f.config("IS_TRUSTSTORE");
      config.put("sourceServerId", source);
      long task = save(config), run = workflows.enqueue(task, null, "admin");
      assertEquals(
          2,
          db.queryForObject(
              "select count(*) from sync_step_servers where step_id in(select id from sync_task_steps where task_id=?)",
              Integer.class,
              task));
      assertEquals(
          2,
          db.queryForObject(
              "select count(*) from sync_run_servers where run_id=?", Integer.class, run));
      servers.save(
          source,
          Map.of(
              "alias",
              "Separate source",
              "revision",
              servers.get(source).get("revision"),
              "type",
              "HTTP",
              "config",
              Map.of("baseUrl", "http://127.0.0.1:1")));
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
    }
  }

  @Test
  void readsSftpSourceAndWritesOriginalPathOverHttp() throws Exception {
    try (var f = new Fixture("JKS")) {
      Files.write(folder.resolve("store.jks"), f.sourceBytes.get());
      var ssh = SshServer.setUpDefaultServer();
      ssh.setPort(0);
      ssh.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(folder.resolve("hostkey")));
      ssh.setPasswordAuthenticator((u, p, session) -> u.equals("user") && p.equals("secret"));
      ssh.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
      ssh.setFileSystemFactory(new VirtualFileSystemFactory(folder));
      ssh.start();
      try {
        long source =
            ((Number)
                    servers
                        .save(
                            null,
                            Map.of(
                                "alias",
                                "Business SFTP",
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
        var config = f.config("IS_TRUSTSTORE");
        config.put("sourceType", "SFTP");
        config.put("sourceServerId", source);
        config.put("sourcePath", "/store.jks");
        long run = workflows.enqueue(save(config), null, "admin");
        runner.executeRun(run);
        assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
        assertEquals("/store.jks", f.updated.get().path("path").asText());
        assertTrue(Files.exists(folder.resolve("store.jks")));
      } finally {
        ssh.stop(true);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"JKS", "PKCS12"})
  void updatesOriginalTruststoreAndWritesDocumentContract(String format) throws Exception {
    try (var f = new Fixture(format)) {
      var config = f.config("IS_TRUSTSTORE");
      long task = save(config);
      assertFalse(
          json.writeValueAsString(workflows.detail(task)).contains("\"password\":\"short\""));
      long run = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      assertEquals("GET", f.readMethod.get());
      assertTrue(f.readQuery.get().contains("path=%2Fstore."));
      assertEquals("application/json", f.readContentType.get());
      JsonNode body = f.updated.get();
      assertEquals("trust", body.path("type").asText());
      assertEquals("store name", body.path("keyStoreName").asText());
      assertEquals("/canonical/store", body.path("path").asText());
      var store = KeyStore.getInstance(format);
      store.load(
          new ByteArrayInputStream(Base64.getDecoder().decode(body.path("content").asText())),
          "short".toCharArray());
      assertEquals(3, store.size());
      assertNotNull(store.getCertificate("original"));
      assertTrue(
          Collections.list(store.aliases()).stream().anyMatch(a -> a.startsWith("business-leaf-")));
      assertFalse(
          json.writeValueAsString(workflows.runDetail(run))
              .contains(body.path("content").asText()));
    }
  }

  @Test
  void wrongSourcePasswordStopsBeforeWriteAndSkipsFollowingComponent() throws Exception {
    try (var f = new Fixture("JKS")) {
      var config = f.config("IS_TRUSTSTORE");
      config.put("password", "wrong");
      long task = save(config);
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("FAILED", workflows.runDetail(run).get("status"));
      assertNull(f.updated.get());
      assertTrue(workflows.runDetail(run).get("errorSummary").toString().contains("处理"));
    }
  }

  @Test
  void createsJksPrivateEntryWithFullChainAndIndependentKeyPassword() throws Exception {
    try (var f = new Fixture("JKS")) {
      var config = f.config("IS_KEYSTORE");
      config.put("certificateId", f.own);
      config.put("keyPassword", "different");
      config.remove("sourceServerId");
      config.remove("sourceType");
      config.remove("sourceUrl");
      config.remove("sourcePath");
      long task = save(config);
      long run = workflows.enqueue(task, null, "admin");
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      var body = f.updated.get();
      assertEquals("key", body.path("type").asText());
      assertEquals("/target/store.jks", body.path("path").asText());
      assertNull(f.readMethod.get());
      var store = KeyStore.getInstance("JKS");
      store.load(
          new ByteArrayInputStream(Base64.getDecoder().decode(body.path("content").asText())),
          "short".toCharArray());
      assertEquals(1, store.size());
      String alias = store.aliases().nextElement();
      assertEquals("business leaf", alias);
      assertNotNull(store.getKey(alias, "different".toCharArray()));
      assertEquals(2, store.getCertificateChain(alias).length);
      assertArrayEquals(
          f.leaf.cert().getEncoded(), store.getCertificateChain(alias)[0].getEncoded());
    }
  }

  /**
   * @description 验证私钥库使用可选自定义别名，空输入仅采用终端证书CN，不追加指纹
   * @author Freddy
   * @date 2026-11-13
   * @param format 私钥库格式
   * @throws Exception 私钥库创建或读取失败
   */
  @ParameterizedTest
  @ValueSource(strings = {"JKS", "PKCS12"})
  void keyStoreUsesOptionalAliasOrOnlyTerminalCn(String format) throws Exception {
    try (var f = new Fixture(format)) {
      for (String configured : List.of("", "   ", "partner-private-key")) {
        var config = f.config("IS_KEYSTORE");
        config.put("certificateId", f.own);
        config.put("alias", configured);
        long task = save(config);
        var validation = SyncData.maps(workflows.validate(task).get("entries"));
        var validatedEntries = SyncData.maps(validation.getFirst().get("entries"));
        assertEquals(1, validatedEntries.size());
        assertEquals(
            configured.isBlank() ? "Business Leaf" : configured,
            validatedEntries.getFirst().get("alias"));
        assertEquals(
            configured,
            SyncData.map(
                    SyncData.maps(workflows.detail(task).get("steps")).getFirst().get("config"))
                .get("alias"));
        long run = workflows.enqueue(task, null, "admin");
        runner.executeRun(run);
        assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
        var store = KeyStore.getInstance(format);
        store.load(
            new ByteArrayInputStream(
                Base64.getDecoder().decode(f.updated.get().path("content").asText())),
            "short".toCharArray());
        assertEquals(
            List.of(configured.isBlank() ? "business leaf" : configured),
            Collections.list(store.aliases()));
        assertNotNull(
            store.getKey(
                configured.isBlank() ? "Business Leaf" : configured, "short".toCharArray()));
        assertEquals(2, store.getCertificateChain(store.aliases().nextElement()).length);
      }
    }
  }

  @Test
  void tnUsesManualChainAndFrozenCustomerIdentity() throws Exception {
    try (var f = new Fixture("JKS")) {
      var config = f.config("TN_CERTIFICATE");
      config.put("certificateId", f.customerAsset);
      config.put("selectionMode", "MANUAL");
      config.put("path", "/tn");
      long task = save(config);
      long run = workflows.enqueue(task, null, "admin");
      db.update("update customers set external_id='changed' where id=?", f.customer);
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      var body = f.updated.get();
      assertEquals("987654321", body.path("id").asText());
      assertEquals("DUNS", body.path("idType").asText());
      assertEquals(2, body.path("certChain").size());
      assertArrayEquals(
          f.root.cert().getEncoded(),
          Base64.getDecoder()
              .decode(body.path("certChain").get(0).path("certificateBase64").asText()));
    }
  }

  private long save(Map<String, Object> config) {
    return ((Number)
            workflows
                .save(
                    null,
                    Map.of(
                        "name",
                        "business test",
                        "state",
                        "READY",
                        "steps",
                        List.of(
                            Map.of(
                                "key",
                                "business",
                                "name",
                                "业务组件",
                                "type",
                                "HTTP",
                                "config",
                                config))),
                    "admin")
                .get("id"))
        .longValue();
  }

  final class Fixture implements AutoCloseable {
    final HttpServer http;
    final AtomicReference<JsonNode> updated = new AtomicReference<>();
    final AtomicReference<byte[]> sourceBytes = new AtomicReference<>();
    final AtomicReference<String> readResponse = new AtomicReference<>();
    final AtomicBoolean echoResponse = new AtomicBoolean(false);
    final AtomicInteger updateStatus = new AtomicInteger(200);
    final AtomicInteger updateCalls = new AtomicInteger();
    final AtomicReference<String> updateCode = new AtomicReference<>("0");
    final AtomicReference<String> echoMarker = new AtomicReference<>();
    final AtomicReference<String> readMethod = new AtomicReference<>(),
        readQuery = new AtomicReference<>(),
        readContentType = new AtomicReference<>();
    final CertificateCrypto.Pair root;
    final CertificateCrypto.Pair leaf;
    final long customer, customerAsset, own, server;
    final String format;

    Fixture(String format) throws Exception {
      this.format = format;
      String name = "Business " + UUID.randomUUID();
      customers.add(new CustomerRequest(name, "", "987654321", "DUNS"), "admin");
      customer = customers.findOrCreateForImport(name);
      root = crypto.issue("ROOT", "Business Root", 1000, null, null, List.of(), "GENERAL");
      leaf =
          crypto.issue("LEAF", "Business Leaf", 90, root.cert(), root.key(), List.of(), "GENERAL");
      var parsed = new CertificateCrypto.Parsed(List.of(root.cert(), leaf.cert()), null);
      customerAsset =
          ((Number) certs.saveImport(parsed, "CUSTOMER", name, "GENERAL", "admin").get("id"))
              .longValue();
      own =
          ((Number)
                  certs
                      .saveImport(
                          new CertificateCrypto.Parsed(
                              List.of(root.cert(), leaf.cert()), leaf.key()),
                          "OWN",
                          null,
                          "GENERAL",
                          "admin")
                      .get("id"))
              .longValue();
      var source = KeyStore.getInstance(format);
      source.load(null, "short".toCharArray());
      source.setCertificateEntry("original", root.cert());
      var bytes = new ByteArrayOutputStream();
      source.store(bytes, "short".toCharArray());
      sourceBytes.set(bytes.toByteArray());
      http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext(
          "/get",
          exchange -> {
            readMethod.set(exchange.getRequestMethod());
            readQuery.set(exchange.getRequestURI().getRawQuery());
            readContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response =
                readResponse.get() == null
                    ? json.writeValueAsBytes(
                        Map.of(
                            "code",
                            "0",
                            "message",
                            "success",
                            "path",
                            "/canonical/store",
                            "content",
                            Base64.getEncoder().encodeToString(sourceBytes.get())))
                    : readResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      for (String endpoint : List.of("/update", "/tn"))
        http.createContext(
            endpoint,
            exchange -> {
              assertEquals("POST", exchange.getRequestMethod());
              updateCalls.incrementAndGet();
              updated.set(json.readTree(exchange.getRequestBody()));
              String message = "success";
              if (echoResponse.get()) {
                message =
                    Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(
                            Base64.getDecoder().decode(updated.get().path("content").asText()));
                echoMarker.set(message.substring(65, 129));
              }
              byte[] response =
                  json.writeValueAsBytes(Map.of("code", updateCode.get(), "message", message));
              exchange.sendResponseHeaders(updateStatus.get(), response.length);
              exchange.getResponseBody().write(response);
              exchange.close();
            });
      http.start();
      server =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              name,
                              "type",
                              "HTTP",
                              "config",
                              Map.of("baseUrl", "http://127.0.0.1:" + http.getAddress().getPort())))
                      .get("id"))
              .longValue();
    }

    Map<String, Object> config(String component) {
      var c = new LinkedHashMap<String, Object>();
      c.put("businessComponent", component);
      c.put("format", format);
      c.put("storePath", "/target/store." + format.toLowerCase());
      c.put("serverId", server);
      c.put("sourceServerId", server);
      c.put("sourceType", "HTTP");
      c.put("sourceUrl", "/get");
      c.put("sourcePath", "/store." + format.toLowerCase());
      c.put("path", "/update");
      c.put("keyStoreName", "store name");
      c.put("password", "short");
      c.put("keyPassword", "short");
      c.put("customerId", customer);
      c.put("purpose", "GENERAL");
      c.put("selectionMode", "AUTO");
      return c;
    }

    public void close() {
      http.stop(0);
    }
  }
}
