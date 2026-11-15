package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.common.ExecutionLog;
import com.certmanage.server.RemoteTransport;
import com.certmanage.server.ServerService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 使用无 SFTP 子系统的真实 SSH 服务验证 Shell 步骤、快照及加密审计
 * @author Freddy
 * @date 2026-11-13
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:sshworkflow;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class SshCommandWorkflowTest {
  @Autowired ServerService servers;
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired WorkflowValidator validator;
  @Autowired RemoteTransport transport;
  @Autowired ExecutionLog logs;
  @Autowired JdbcTemplate db;
  @TempDir Path folder;
  final List<String> received = new CopyOnWriteArrayList<>();

  /**
   * @description 验证纯 SSH 连接、多行固定命令、静默等待与输出只进入加密审计
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 测试服务启动失败时抛出
   */
  @Test
  void executesFixedMultilineCommandWithoutSftpOrFileInput() throws Exception {
    try (var ssh = target()) {
      long server = saveServer(ssh, Map.of());
      var connectionTest = transport.test(server);
      assertEquals(true, connectionTest.get("success"), connectionTest.toString());
      assertFalse(((Map<?, ?>) servers.get(server).get("config")).containsKey("password"));
      String command = "echo fixed-command-marker\necho fixed-stderr-marker >&2";
      long run = execute(List.of(step("shell", Map.of("serverId", server, "command", command))));
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      assertEquals(List.of(command), received);
      assertFalse(workflows.runDetail(run).toString().contains("fixed-stdout-marker"));
      assertTrue(original(run, "SSH_RESULT").contains("fixed-stdout-marker"));
      assertTrue(original(run, "SSH_RESULT").contains("fixed-stderr-marker"));
      assertTrue(original(run, "SSH_COMMAND").contains(command));
      long silent =
          execute(
              List.of(
                  step(
                      "silent",
                      Map.of("serverId", server, "command", "silent", "timeoutSeconds", 5))));
      assertEquals("SUCCESS", workflows.runDetail(silent).get("status"));
    }
  }

  /**
   * @description 认证阶段超过服务器期限应报告超时，并区别于凭据错误
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 测试服务启动失败时抛出
   */
  @Test
  void distinguishesAuthenticationTimeoutFromRejectedCredentials() throws Exception {
    try (var ssh = target()) {
      long wrong = saveServer(ssh, Map.of("password", "wrong"));
      assertEquals("AUTHENTICATION_FAILED", transport.test(wrong).get("errorCode"));
      ssh.setPasswordAuthenticator(
          (user, password, session) -> {
            try {
              Thread.sleep(1800);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            return true;
          });
      long slow = saveServer(ssh, Map.of());
      assertEquals("TIMEOUT", transport.test(slow).get("errorCode"));
    }
  }

  /**
   * @description 非零退出与超时必须失败并停止后续步骤，同时保留已收到的输出
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 测试服务启动失败时抛出
   */
  @Test
  void failsOnExitStatusAndTimeoutAndSkipsRemainingSteps() throws Exception {
    try (var ssh = target()) {
      long server = saveServer(ssh, Map.of());
      for (String command : List.of("fail", "timeout", "missing-exit")) {
        long run =
            execute(
                List.of(
                    step(
                        "first",
                        Map.of("serverId", server, "command", command, "timeoutSeconds", 1)),
                    step("next", Map.of("serverId", server, "command", "never"))));
        var detail = workflows.runDetail(run);
        assertEquals("FAILED", detail.get("status"), detail.toString());
        assertTrue(detail.toString().contains("SKIPPED"));
        assertTrue(original(run, "SSH_RESULT").contains("partial-output-marker"));
      }
      assertFalse(received.contains("never"));
    }
  }

  /**
   * @description 超过每流保留上限仍需排空两路输出并取得退出码，审计标明截断
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 测试服务启动失败时抛出
   */
  @Test
  void drainsLargeStdoutAndStderrWithBoundedCapture() throws Exception {
    try (var ssh = target()) {
      long server = saveServer(ssh, Map.of());
      long run =
          execute(
              List.of(
                  step(
                      "large",
                      Map.of("serverId", server, "command", "large", "timeoutSeconds", 10))));
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      String raw = original(run, "SSH_RESULT");
      assertTrue(raw.contains("stdoutTruncated=true"));
      assertTrue(raw.contains("stderrTruncated=true"));
      assertTrue(raw.contains("stdoutBytes=1048699"));
      assertTrue(raw.contains("stderrBytes=1048699"));
      assertTrue(raw.length() < 3 * 1024 * 1024);
    }
  }

  /**
   * @description 验证密钥认证、排队配置冻结、服务器引用保护及停用检查
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 密钥生成或测试服务失败时抛出
   */
  @Test
  void usesPrivateKeyAndFrozenConnectionAndHonorsDisabledServer() throws Exception {
    try (var ssh = target()) {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      ssh.setPublickeyAuthenticator(
          (user, key, session) ->
              java.util.Arrays.equals(key.getEncoded(), pair.getPublic().getEncoded()));
      String pem =
          "-----BEGIN PRIVATE KEY-----\n"
              + Base64.getMimeEncoder(64, new byte[] {'\n'})
                  .encodeToString(pair.getPrivate().getEncoded())
              + "\n-----END PRIVATE KEY-----\n";
      long server = saveServer(ssh, Map.of("authType", "KEY", "privateKey", pem));
      var connectionTest = transport.test(server);
      assertEquals(true, connectionTest.get("success"), connectionTest.toString());
      long task = task(List.of(step("shell", Map.of("serverId", server, "command", "snapshot"))));
      long run = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
      var edited = new LinkedHashMap<>(servers.get(server));
      edited.put(
          "config",
          Map.of(
              "host",
              "127.0.0.1",
              "port",
              ssh.getPort(),
              "username",
              "user",
              "authType",
              "PASSWORD",
              "password",
              "wrong"));
      servers.save(server, edited);
      runner.executeRun(run);
      assertEquals("SUCCESS", workflows.runDetail(run).get("status"));
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> servers.delete(server));
      long disabled = workflows.enqueue(task, UUID.randomUUID().toString(), "admin");
      edited = new LinkedHashMap<>(servers.get(server));
      edited.put("enabled", false);
      servers.save(server, edited);
      runner.executeRun(disabled);
      assertEquals("FAILED", workflows.runDetail(disabled).get("status"));
      assertEquals(1, received.stream().filter("snapshot"::equals).count());
    }
  }

  /**
   * @description 拒绝额外文件输入、空命令、非法超时及将 Shell 步骤作为文件来源
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 测试服务启动失败时抛出
   */
  @Test
  void validatesFixedInputsAndServerType() throws Exception {
    try (var ssh = target()) {
      long server = saveServer(ssh, Map.of());
      for (var extra :
          List.<Map<String, Object>>of(
              Map.of("sourceStep", "earlier"),
              Map.of("certificateId", 99),
              Map.of("command", " "),
              Map.of("command", "a\u0000b"),
              Map.of("timeoutSeconds", 0),
              Map.of("timeoutSeconds", 301),
              Map.of("timeoutSeconds", 1.5))) {
        var config = new LinkedHashMap<String, Object>(Map.of("serverId", server, "command", "ok"));
        config.putAll(extra);
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.prepare(List.of(step("shell", config)), true),
            extra.toString());
      }
      var read =
          new LinkedHashMap<String, Object>(
              Map.of(
                  "key",
                  "read",
                  "type",
                  "SFTP",
                  "config",
                  Map.of("serverId", server, "operation", "READ", "sourcePath", "/file")));
      assertThrows(IllegalArgumentException.class, () -> validator.prepare(List.of(read), true));
      var consumer =
          new LinkedHashMap<String, Object>(
              Map.of("key", "upload", "type", "SFTP", "config", Map.of("sourceStep", "shell")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              validator.prepare(
                  List.of(step("shell", Map.of("serverId", server, "command", "ok")), consumer),
                  false));
    }
  }

  /**
   * @description 创建只接受 exec 命令、不安装 SFTP 子系统的受控远端
   * @author Freddy
   * @date 2026-11-13
   * @return 已启动的本地 SSH 测试服务
   * @throws Exception 服务启动失败时抛出
   */
  private SshServer target() throws Exception {
    var ssh = SshServer.setUpDefaultServer();
    ssh.setHost("127.0.0.1");
    ssh.setPort(0);
    ssh.setKeyPairProvider(
        new SimpleGeneratorHostKeyProvider(folder.resolve(UUID.randomUUID() + ".key")));
    ssh.setPasswordAuthenticator(
        (user, password, session) -> "user".equals(user) && "secret".equals(password));
    ssh.setCommandFactory(
        (channel, command) ->
            new AbstractCommandSupport(command, null) {
              /**
               * @description 按命令标记模拟输出、阻塞及退出状态，避免依赖宿主机 Shell
               * @author Freddy
               * @date 2026-11-13
               */
              @Override
              public void run() {
                received.add(command);
                try {
                  if (command.equals("large")) {
                    byte[] data = new byte[1048699];
                    Arrays.fill(data, (byte) 'x');
                    getOutputStream().write(data);
                    getErrorStream().write(data);
                  } else if (Set.of("fail", "timeout", "missing-exit").contains(command)) {
                    getOutputStream()
                        .write("partial-output-marker".getBytes(StandardCharsets.UTF_8));
                    getOutputStream().flush();
                    if (command.equals("timeout")) Thread.sleep(5000);
                    if (command.equals("missing-exit")) {
                      channel.close(true);
                      return;
                    }
                  } else {
                    if (command.equals("silent")) Thread.sleep(1800);
                    getOutputStream()
                        .write("fixed-stdout-marker 中文".getBytes(StandardCharsets.UTF_8));
                    getErrorStream().write("fixed-stderr-marker".getBytes(StandardCharsets.UTF_8));
                  }
                  getOutputStream().flush();
                  getErrorStream().flush();
                  onExit(command.equals("fail") ? 7 : 0);
                } catch (Exception stopped) {
                  onExit(1);
                }
              }
            });
    ssh.start();
    return ssh;
  }

  /**
   * @description 保存独立 SSH 服务器并允许测试替换认证方式
   * @author Freddy
   * @date 2026-11-13
   * @param ssh 本地测试服务
   * @param extra 认证字段覆盖值
   * @return 管理服务器 ID
   */
  private long saveServer(SshServer ssh, Map<String, Object> extra) {
    var config =
        new LinkedHashMap<String, Object>(
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
                "connectTimeout",
                3,
                "readTimeout",
                1));
    config.putAll(extra);
    return ((Number)
            servers
                .save(
                    null,
                    Map.of("alias", "SSH " + UUID.randomUUID(), "type", "SSH", "config", config))
                .get("id"))
        .longValue();
  }

  /**
   * @description 构造无文件输入的独立 Shell 步骤
   * @author Freddy
   * @date 2026-11-13
   * @param key 步骤标识
   * @param config 固定参数
   * @return 可由校验器规范化的步骤
   */
  private Map<String, Object> step(String key, Map<String, Object> config) {
    return new LinkedHashMap<>(Map.of("key", key, "type", "SSH", "config", config));
  }

  /**
   * @description 保存就绪任务，验证数据库协议和组件约束兼容 SSH
   * @author Freddy
   * @date 2026-11-13
   * @param steps 有序步骤
   * @return 任务 ID
   */
  private long task(List<Map<String, Object>> steps) {
    return ((Number)
            workflows
                .save(null, Map.of("name", "SSH test", "state", "READY", "steps", steps), "admin")
                .get("id"))
        .longValue();
  }

  /**
   * @description 通过真实入队和执行路径执行流程
   * @author Freddy
   * @date 2026-11-13
   * @param steps 有序步骤
   * @return 运行 ID
   */
  private long execute(List<Map<String, Object>> steps) {
    long run = workflows.enqueue(task(steps), UUID.randomUUID().toString(), "admin");
    runner.executeRun(run);
    return run;
  }

  /**
   * @description 读取指定运行首步骤的加密原始事件，验证列表与详情分离
   * @author Freddy
   * @date 2026-11-13
   * @param run 运行 ID
   * @param kind 原始事件种类
   * @return 解密后的载荷文本
   */
  @SuppressWarnings("unchecked")
  private String original(long run, String kind) {
    long event =
        db.queryForObject(
            "select min(id) from execution_logs where run_id=? and step_key is not null",
            Long.class,
            run);
    var events = (List<Map<String, Object>>) logs.detail(event).get("rawEvents");
    var entry = events.stream().filter(e -> kind.equals(e.get("kind"))).findFirst().orElseThrow();
    return logs.original(event, entry.get("id").toString()).toString();
  }
}
