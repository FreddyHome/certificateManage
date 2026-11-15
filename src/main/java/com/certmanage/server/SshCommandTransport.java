package com.certmanage.server;

import com.certmanage.common.Errors;
import com.certmanage.common.RawExecutionLog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * @description 通过独立 SSH exec 通道执行固定命令，有界采集输出并写入加密审计
 * @author Freddy
 * @date 2026-11-13
 */
final class SshCommandTransport {
  /**
   * @description 建立认证连接或执行命令，连接测试不打开 SFTP 子系统且不执行命令
   * @author Freddy
   * @date 2026-11-13
   * @param config 已解密的 SSH 服务器版本配置
   * @param step 固定命令和执行超时，测试时可为空映射
   * @param test 是否仅测试连接认证
   * @return 不含原始输出和命令内容的结果摘要
   * @throws RemoteFailure 连接、认证、命令退出或超时失败时抛出
   */
  static Map<String, Object> call(
      Map<String, Object> config, Map<String, Object> step, boolean test) {
    var raw = RawExecutionLog.sink();
    String stage = "CONNECT";
    long started = System.nanoTime();
    var stdout = new Capture();
    var stderr = new Capture();
    Integer exitCode = null;
    String exitSignal = null;
    boolean complete = false;
    boolean requested = false;
    var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
    var authenticationTimedOut = new java.util.concurrent.atomic.AtomicBoolean();
    try (var client = SshClient.setUpDefaultClient()) {
      // 沿用项目已确认的自管服务器信任方式，与现有 SFTP 一致。
      client.setServerKeyVerifier((session, address, key) -> true);
      client.start();
      raw.record(
          "SSH_CONNECT",
          "SSH连接请求",
          Map.of(
              "host",
              config.get("host"),
              "port",
              config.getOrDefault("port", 22),
              "username",
              config.get("username")));
      try (var session =
          client
              .connect(
                  text(config, "username", ""),
                  text(config, "host", ""),
                  Integer.parseInt(text(config, "port", "22")))
              .verify(Duration.ofSeconds(Long.parseLong(text(config, "connectTimeout", "10"))))
              .getSession()) {
        try (var deadline =
            new RemoteTransport.Deadline(
                () -> {
                  authenticationTimedOut.set(true);
                  session.close(true);
                },
                Long.parseLong(text(config, "readTimeout", "30")))) {
          stage = "KEY_LOADING";
          if ("KEY".equals(config.get("authType"))) {
            var keys =
                SecurityUtils.loadKeyPairIdentities(
                    session,
                    NamedResource.ofName("configured-key"),
                    new ByteArrayInputStream(
                        text(config, "privateKey", "").getBytes(StandardCharsets.UTF_8)),
                    FilePasswordProvider.of(text(config, "passphrase", "")));
            for (var key : keys) session.addPublicKeyIdentity(key);
          } else session.addPasswordIdentity(text(config, "password", ""));
          stage = "AUTH";
          session
              .auth()
              .verify(Duration.ofSeconds(Long.parseLong(text(config, "readTimeout", "30"))));
          if (deadline.expired) throw new RemoteFailure("TIMEOUT", stage, "SSH认证超时");
        }
        raw.record("SSH_AUTH", "SSH认证成功", Map.of("authenticated", session.isAuthenticated()));
        if (test) return Map.of("connected", true);
        String command = text(step, "command", "");
        Errors.require(
            !command.isBlank() && !command.contains("\0") && command.length() <= 100000,
            "Shell命令必填且不超过100000字符，不能包含空字符");
        int timeout = Integer.parseInt(text(step, "timeoutSeconds", "60"));
        Errors.require(timeout >= 1 && timeout <= 300, "Shell执行超时须为1至300秒");
        stage = "EXEC";
        // 静默命令受步骤总执行期限控制，不能因服务器较短读写超时而提前中断。
        CoreModuleProperties.IDLE_TIMEOUT.set(session, Duration.ZERO);
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(session, Duration.ZERO);
        raw.record(
            "SSH_COMMAND", "Shell命令请求", Map.of("command", command, "timeoutSeconds", timeout));
        requested = true;
        try (var channel = session.createExecChannel(command);
            var deadline =
                new RemoteTransport.Deadline(
                    () -> {
                      timedOut.set(true);
                      session.close(true);
                    },
                    timeout)) {
          channel.setIn(new ByteArrayInputStream(new byte[0]));
          channel.setOut(stdout);
          channel.setErr(stderr);
          channel.open().verify(Duration.ofSeconds(timeout));
          var events =
              channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(timeout));
          exitCode = channel.getExitStatus();
          exitSignal = channel.getExitSignal();
          if (deadline.expired || events.contains(ClientChannelEvent.TIMEOUT))
            throw new RemoteFailure("TIMEOUT", stage, "Shell执行超时，已关闭连接，请核查远端命令状态");
          complete = events.contains(ClientChannelEvent.CLOSED);
          if (exitCode == null || exitSignal != null)
            throw new RemoteFailure("SSH_EXIT_STATUS_MISSING", stage, "Shell未正常返回退出码，请核查远端命令状态");
          if (exitCode != 0)
            throw new RemoteFailure("SSH_COMMAND_FAILED", stage, "Shell命令执行失败，退出码：" + exitCode);
          return Map.of(
              "exitCode",
              exitCode,
              "elapsedMs",
              (System.nanoTime() - started) / 1000000,
              "stdoutBytes",
              stdout.total(),
              "stderrBytes",
              stderr.total(),
              "stdoutTruncated",
              stdout.truncated(),
              "stderrTruncated",
              stderr.truncated(),
              "message",
              "Shell命令执行成功，退出码：0");
        }
      }
    } catch (Exception failure) {
      raw.exception(failure);
      if (timedOut.get()) throw new RemoteFailure("TIMEOUT", stage, "Shell执行超时，已关闭连接，请核查远端命令状态");
      if (authenticationTimedOut.get())
        throw new RemoteFailure("TIMEOUT", stage, "SSH认证超时，请检查服务器响应和认证超时设置");
      var diagnostic = RemoteFailure.of("SSH", stage, config, failure);
      // SSHD 自身的 verify 超时可能先于关闭连接的定时器触发，优先保留超时诊断。
      if (diagnostic.code().equals("TIMEOUT")) throw diagnostic;
      if (!(failure instanceof RemoteFailure) && stage.equals("KEY_LOADING"))
        throw new RemoteFailure("SSH_KEY_INVALID", stage, "SSH私钥读取失败：请检查私钥格式和私钥口令");
      if (!(failure instanceof RemoteFailure) && stage.equals("AUTH"))
        throw new RemoteFailure("AUTHENTICATION_FAILED", stage, "SSH认证失败：请检查用户名、认证凭据及服务器授权配置");
      throw diagnostic;
    } finally {
      if (requested) {
        var result = new LinkedHashMap<String, Object>();
        result.put("stdout", stdout.content());
        result.put("stderr", stderr.content());
        result.put("stdoutBytes", stdout.total());
        result.put("stderrBytes", stderr.total());
        result.put("stdoutTruncated", stdout.truncated());
        result.put("stderrTruncated", stderr.truncated());
        result.put("exitCode", exitCode);
        result.put("exitSignal", exitSignal);
        result.put("complete", complete);
        result.put("elapsedMs", (System.nanoTime() - started) / 1000000);
        raw.record("SSH_RESULT", "Shell执行原始结果", result);
      }
    }
  }

  /**
   * @description 读取配置文本并为缺省值提供默认值
   * @author Freddy
   * @date 2026-11-13
   * @param config 配置映射
   * @param key 字段名
   * @param fallback 默认文本
   * @return 字段文本
   */
  private static String text(Map<String, Object> config, String key, String fallback) {
    return Objects.toString(config.get(key), fallback);
  }

  /**
   * @description 每路最多保留一 MiB，继续接收并计数剩余内容以避免远端窗口阻塞
   * @author Freddy
   * @date 2026-11-13
   */
  private static final class Capture extends OutputStream {
    private static final int LIMIT = 1024 * 1024;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private long total;

    /**
     * @description 接收单个字节，达到保留上限后仅计数
     * @author Freddy
     * @date 2026-11-13
     * @param value 输入字节
     */
    @Override
    public synchronized void write(int value) {
      if (bytes.size() < LIMIT) bytes.write(value);
      total++;
    }

    /**
     * @description 接收一段字节，保留前一 MiB 并持续排空其余内容
     * @author Freddy
     * @date 2026-11-13
     * @param data 输入缓冲区
     * @param offset 起始偏移
     * @param length 输入字节数
     */
    @Override
    public synchronized void write(byte[] data, int offset, int length) {
      bytes.write(data, offset, Math.min(length, LIMIT - bytes.size()));
      total += length;
    }

    /**
     * @description 读取已保留内容，以 UTF-8 解释远端输出
     * @author Freddy
     * @date 2026-11-13
     * @return 保留的输出文本
     */
    synchronized String content() {
      return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * @description 返回实际接收到的总字节数
     * @author Freddy
     * @date 2026-11-13
     * @return 包含未保留部分的总字节数
     */
    synchronized long total() {
      return total;
    }

    /**
     * @description 判断保留上限是否造成截断
     * @author Freddy
     * @date 2026-11-13
     * @return 是否有未保留的输出
     */
    synchronized boolean truncated() {
      return total > LIMIT;
    }
  }
}
