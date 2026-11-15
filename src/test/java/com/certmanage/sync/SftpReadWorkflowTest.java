package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.server.ServerService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 通过真实 SFTP 与 HTTP 验证远端文件读取及后续组件文件输入
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
class SftpReadWorkflowTest {
  @Autowired ServerService servers;
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired WorkflowValidator validator;
  @Autowired com.certmanage.common.ExecutionLog logs;
  @TempDir Path folder;

  /**
   * @description 验证读取保留文件、后续上传内容及原文件名、三种 HTTP 输入以及失败停止
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 本地测试服务器或文件操作失败时抛出
   */
  @Test
  void readsOnceAndSuppliesMultipleConsumersWithoutPersistingContents() throws Exception {
    var ssh = SshServer.setUpDefaultServer();
    ssh.setPort(0);
    ssh.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(folder.resolve("hostkey")));
    ssh.setPasswordAuthenticator(
        (user, password, session) -> user.equals("user") && password.equals("secret"));
    ssh.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    var remote = Files.createDirectory(folder.resolve("remote"));
    Files.createDirectories(remote.resolve("input"));
    Files.createDirectories(remote.resolve("copy"));
    var payload = "private-file-marker-不能写入日志\u0000\u0001".getBytes(StandardCharsets.UTF_8);
    Files.write(remote.resolve("input/source.p12"), payload);
    ssh.setFileSystemFactory(new VirtualFileSystemFactory(remote));
    ssh.start();
    var received = new ConcurrentHashMap<String, byte[]>();
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/",
        exchange -> {
          received.put(
              exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    http.start();
    try {
      long sftp =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              "Read test SFTP",
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
                                  "PASSWORD",
                                  "readTimeout",
                                  5,
                                  "connectTimeout",
                                  3)))
                      .get("id"))
              .longValue();
      long web =
          ((Number)
                  servers
                      .save(
                          null,
                          Map.of(
                              "alias",
                              "Read test HTTP",
                              "type",
                              "HTTP",
                              "config",
                              Map.of("baseUrl", "http://127.0.0.1:" + http.getAddress().getPort())))
                      .get("id"))
              .longValue();
      var read =
          step(
              "read",
              "SFTP",
              Map.of("serverId", sftp, "operation", "READ", "sourcePath", "/input/source.p12"));
      var upload =
          step(
              "upload",
              "SFTP",
              Map.of("serverId", sftp, "sourceStep", "read", "remoteDirectory", "/copy"));
      var binary =
          step(
              "binary",
              "HTTP",
              Map.of(
                  "serverId",
                  web,
                  "sourceStep",
                  "read",
                  "path",
                  "/binary",
                  "method",
                  "POST",
                  "bodyMode",
                  "BINARY"));
      var multipart =
          step(
              "multipart",
              "HTTP",
              Map.of(
                  "serverId",
                  web,
                  "sourceStep",
                  "read",
                  "path",
                  "/multipart",
                  "method",
                  "POST",
                  "bodyMode",
                  "MULTIPART"));
      var json =
          step(
              "json",
              "HTTP",
              Map.of(
                  "serverId",
                  web,
                  "sourceStep",
                  "read",
                  "path",
                  "/json",
                  "method",
                  "POST",
                  "bodyMode",
                  "JSON",
                  "body",
                  "{\"file\":\"${fileBase64}\"}"));
      long run = run(List.of(read, upload, binary, multipart, json));
      var detail = workflows.runDetail(run);
      assertEquals("SUCCESS", detail.get("status"), detail.toString());
      assertArrayEquals(payload, Files.readAllBytes(remote.resolve("input/source.p12")));
      assertArrayEquals(payload, Files.readAllBytes(remote.resolve("copy/source.p12")));
      assertArrayEquals(payload, received.get("/binary"));
      var multipartBody = new String(received.get("/multipart"), StandardCharsets.ISO_8859_1);
      assertTrue(multipartBody.contains("filename=\"source.p12\""));
      assertTrue(multipartBody.contains(new String(payload, StandardCharsets.ISO_8859_1)));
      assertEquals(
          "{\"file\":\"" + Base64.getEncoder().encodeToString(payload) + "\"}",
          new String(received.get("/json"), StandardCharsets.UTF_8));
      assertFalse(detail.toString().contains("private-file-marker"));
      assertFalse(detail.toString().contains(Base64.getEncoder().encodeToString(payload)));
      assertTrue(detail.toString().contains("source.p12"));
      var events = logs.list("SYNC", run, null, null, null, 1, 100).toString();
      assertFalse(events.contains("private-file-marker"));
      assertFalse(events.contains(Base64.getEncoder().encodeToString(payload)));
      assertTrue(events.contains("ARTIFACTS_RELEASED"));

      var missing =
          step(
              "read",
              "SFTP",
              Map.of("serverId", sftp, "operation", "READ", "sourcePath", "/missing.p12"));
      received.clear();
      var failure = workflows.runDetail(run(List.of(missing, binary)));
      assertEquals("FAILED", failure.get("status"));
      assertTrue(failure.toString().contains("SKIPPED"));
      assertTrue(received.isEmpty());

      var directory =
          step(
              "read",
              "SFTP",
              Map.of("serverId", sftp, "operation", "READ", "sourcePath", "/input"));
      assertEquals("FAILED", workflows.runDetail(run(List.of(directory))).get("status"));
      Files.write(remote.resolve("empty.bin"), new byte[0]);
      assertEquals(
          "SUCCESS",
          workflows
              .runDetail(
                  run(
                      List.of(
                          step(
                              "empty",
                              "SFTP",
                              Map.of(
                                  "serverId",
                                  sftp,
                                  "operation",
                                  "READ",
                                  "sourcePath",
                                  "/empty.bin")))))
              .get("status"));
      try (var big = new java.io.RandomAccessFile(remote.resolve("big.bin").toFile(), "rw")) {
        big.setLength(10 * 1024 * 1024 + 1);
      }
      var tooBig =
          step(
              "read",
              "SFTP",
              Map.of("serverId", sftp, "operation", "READ", "sourcePath", "/big.bin"));
      var oversized = workflows.runDetail(run(List.of(tooBig, binary)));
      assertEquals("FAILED", oversized.get("status"));
      assertTrue(oversized.toString().contains("SKIPPED"));
      try (var big = new java.io.RandomAccessFile(remote.resolve("large.bin").toFile(), "rw")) {
        big.setLength(9 * 1024 * 1024);
      }
      var manyReads = new ArrayList<Map<String, Object>>();
      for (int i = 1; i <= 3; i++)
        manyReads.add(
            step(
                "read" + i,
                "SFTP",
                Map.of("serverId", sftp, "operation", "READ", "sourcePath", "/large.bin")));
      manyReads.add(
          step(
              "after",
              "HTTP",
              Map.of(
                  "serverId",
                  web,
                  "sourceStep",
                  "read3",
                  "path",
                  "/after",
                  "method",
                  "POST",
                  "bodyMode",
                  "BINARY")));
      var overBudget = workflows.runDetail(run(manyReads));
      assertEquals("FAILED", overBudget.get("status"));
      assertTrue(overBudget.toString().contains("SKIPPED"));
      assertTrue(received.isEmpty());
    } finally {
      http.stop(0);
      ssh.stop(true);
    }
  }

  /**
   * @description 拒绝前向引用、无文件输出步骤、读取操作额外输入及非法路径
   * @author Freddy
   * @date 2026-11-13
   */
  @Test
  void rejectsInvalidSourcesAndReadPaths() {
    var read = step("read", "SFTP", Map.of("operation", "READ", "sourcePath", "/file.p12"));
    var consumer = step("consumer", "SFTP", Map.of("sourceStep", "read"));
    assertThrows(
        IllegalArgumentException.class, () -> validator.prepare(List.of(consumer, read), false));
    var move = step("read", "SFTP", Map.of("operation", "MOVE"));
    assertThrows(
        IllegalArgumentException.class, () -> validator.prepare(List.of(move, consumer), false));
    var upload = step("read", "SFTP", Map.of("operation", "UPLOAD"));
    assertThrows(
        IllegalArgumentException.class, () -> validator.prepare(List.of(upload, consumer), false));
    var extraInput =
        step(
            "next",
            "SFTP",
            Map.of("operation", "READ", "sourcePath", "/file.p12", "sourceStep", "read"));
    assertThrows(
        IllegalArgumentException.class, () -> validator.prepare(List.of(read, extraInput), false));
    for (String path :
        List.of("relative.p12", "/file/", "/../file", "/a\\file", "/a\nfile", "/.")) {
      var invalid = step("read", "SFTP", Map.of("operation", "READ", "sourcePath", path));
      assertThrows(
          IllegalArgumentException.class, () -> validator.prepare(List.of(invalid), false), path);
    }
  }

  /**
   * @description 构造可执行任务并同步执行，保留真实保存、快照及运行路径
   * @author Freddy
   * @date 2026-11-13
   * @param steps 按顺序执行的步骤
   * @return 运行 ID
   */
  private long run(List<Map<String, Object>> steps) {
    var saved =
        workflows.save(
            null, Map.of("name", "SFTP read test", "state", "READY", "steps", steps), "admin");
    long run =
        workflows.enqueue(
            ((Number) saved.get("id")).longValue(), UUID.randomUUID().toString(), "admin");
    runner.executeRun(run);
    return run;
  }

  /**
   * @description 构造可由校验器规范化的流程步骤
   * @author Freddy
   * @date 2026-11-13
   * @param key 步骤标识
   * @param type 组件类型
   * @param config 组件输入
   * @return 流程步骤
   */
  private Map<String, Object> step(String key, String type, Map<String, Object> config) {
    return new LinkedHashMap<>(Map.of("key", key, "type", type, "config", config));
  }
}
