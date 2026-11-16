package com.certmanage.server;

import com.certmanage.common.Errors;
import com.certmanage.common.RawExecutionLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.sftp.client.*;
import org.springframework.stereotype.Service;

@Service
/**
 * @description 执行远端HTTP、SFTP和SSH命令调用并提供安全诊断
 * @author Freddy
 * @date 2026-11-12
 */
public class RemoteTransport implements com.certmanage.common.PerformanceSource {
  final ServerService servers;
  final ObjectMapper json;
  private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "remote-deadlines");
            t.setDaemon(true);
            return t;
          });

  /**
   * @description 管理远端读写操作的截止时间
   * @author Freddy
   * @date 2026-11-12
   */
  static final class Deadline implements AutoCloseable {
    final java.util.concurrent.ScheduledFuture<?> timer;
    volatile boolean expired;

    /**
     * @description 管理远端读写操作的截止时间
     * @author Freddy
     * @date 2026-11-12
     * @param close 操作所需的配置或依赖
     * @param seconds 操作所需的配置或依赖
     */
    Deadline(Runnable close, long seconds) {
      timer =
          DEADLINES.schedule(
              () -> {
                expired = true;
                close.run();
              },
              seconds,
              java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * @description 取消截止时间定时器
     * @author Freddy
     * @date 2026-11-12
     */
    public void close() {
      timer.cancel(false);
    }
  }

  /**
   * @description 执行远端HTTP与SFTP调用并提供安全诊断
   * @author Freddy
   * @date 2026-11-12
   * @param s 操作所需的配置或依赖
   * @param j 操作所需的配置或依赖
   */
  public RemoteTransport(ServerService s, ObjectMapper j) {
    servers = s;
    json = j;
  }

  /**
   * @description 读取配置文本并提供默认值
   * @author Freddy
   * @date 2026-11-12
   * @param m 操作所需的配置或依赖
   * @param k 操作所需的配置或依赖
   * @param d 操作所需的配置或依赖
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  static String str(Map<String, Object> m, String k, String d) {
    return Objects.toString(m.get(k), d);
  }

  /**
   * @description 验证服务器状态并执行远端请求
   * @author Freddy
   * @date 2026-11-12
   * @param serverId 服务器标识
   * @param configId 冻结连接版本标识
   * @param step 执行步骤快照
   * @param data 待发送文件字节，可为空
   * @param filename 安全文件名
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  public Map<String, Object> execute(
      long serverId, long configId, Map<String, Object> step, byte[] data, String filename) {
    try (var budget = RawExecutionLog.reserveRemoteCapacity()) {
      Errors.require(!"READ".equals(step.get("operation")), "读取操作必须使用文件输出入口");
      Errors.require(servers.enabled(serverId), "服务器已停用");
      var c = versionedConnection(configId);
      try {
        if ("SSH".equals(c.get("type"))) return SshCommandTransport.call(c, step, false);
        return "SFTP".equals(c.get("type"))
            ? sftp(c, step, data, filename, false, 0).metadata()
            : http(c, step, data, filename, false);
      } catch (Exception e) {
        RawExecutionLog.exception(e);
        throw RemoteFailure.of(str(c, "type", "REMOTE"), "REMOTE_CALL", c, e);
      }
    }
  }

  /**
   * @description 读取冻结服务器版本的远端文件，将原始字节交给流程内存产物上下文
   * @author Freddy
   * @date 2026-11-13
   * @param serverId 服务器标识，必须仍然启用
   * @param configId 冻结连接版本标识
   * @param step 包含 READ 操作及 sourcePath 的步骤配置
   * @param maxBytes 当前流程允许读取的最大字节数，不超过 10 MiB
   * @return 文件内容、原文件名及源路径；原文另存加密审计详情
   */
  public RemoteFile readFile(long serverId, long configId, Map<String, Object> step, int maxBytes) {
    try (var budget = RawExecutionLog.reserveRemoteCapacity()) {
      Errors.require(servers.enabled(serverId), "服务器已停用");
      Errors.require("READ".equals(step.get("operation")), "读取操作类型错误");
      Errors.require(maxBytes >= 0 && maxBytes <= 10 * 1024 * 1024, "文件读取限制不合法");
      var connection = versionedConnection(configId);
      Errors.require("SFTP".equals(connection.get("type")), "读取文件必须选择 SFTP 服务器");
      try {
        return sftp(connection, step, null, null, false, maxBytes).file();
      } catch (Exception failure) {
        RawExecutionLog.exception(failure);
        throw RemoteFailure.of("SFTP", "READ", connection, failure);
      }
    }
  }

  /**
   * @description 仅在执行内存中传递远端文件，元数据与原始内容分别处理
   * @author Freddy
   * @date 2026-11-13
   */
  public record RemoteFile(byte[] bytes, String filename, String sourcePath) {}

  /**
   * @description 分离普通结果中的 SFTP 元数据与另存加密审计的文件正文
   * @author Freddy
   * @date 2026-11-13
   */
  private record SftpResult(Map<String, Object> metadata, RemoteFile file) {}

  /**
   * @description 测试服务器连接并返回安全诊断
   * @author Freddy
   * @date 2026-11-12
   * @param id 记录标识
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  public Map<String, Object> test(long id) {
    try (var budget = RawExecutionLog.reserveRemoteCapacity()) {
      var snap = servers.snapshot(id);
      var c = versionedConnection(((Number) snap.get("configId")).longValue());
      long begin = System.nanoTime();
      try {
        if ("SSH".equals(c.get("type"))) {
          var connected = SshCommandTransport.call(c, Map.of(), true);
          return Map.of(
              "success",
              true,
              "elapsedMs",
              (System.nanoTime() - begin) / 1000000,
              "result",
              connected);
        }
        var result =
            "SFTP".equals(c.get("type"))
                ? sftp(c, Map.of(), null, null, true, 0).metadata()
                : http(c, Map.of(), null, null, true);
        return Map.of(
            "success", true, "elapsedMs", (System.nanoTime() - begin) / 1000000, "result", result);
      } catch (Exception e) {
        RawExecutionLog.exception(e);
        var failure = RemoteFailure.of(str(c, "type", "REMOTE"), "REMOTE_CALL", c, e);
        return Map.of(
            "success",
            false,
            "elapsedMs",
            (System.nanoTime() - begin) / 1000000,
            "message",
            failure.getMessage(),
            "errorCode",
            failure.code(),
            "stage",
            failure.stage());
      }
    }
  }

  /**
   * @description 复用认证连接与超时，执行 SFTP 测试、上传、移动或有界文件读取
   * @author Freddy
   * @date 2026-11-12
   * @param c 组件配置
   * @param step 执行步骤快照
   * @param data 待发送文件字节，可为空
   * @param filename 安全文件名
   * @param test 是否仅测试连接
   * @param maxBytes 读取操作的最大字节数，其他操作忽略
   * @return 元数据及可选的内存文件，文件正文另存加密审计详情
   * @throws Exception 远端操作、证书解析或文件处理失败
   */
  private SftpResult sftp(
      Map<String, Object> c,
      Map<String, Object> step,
      byte[] data,
      String filename,
      boolean test,
      int maxBytes)
      throws Exception {
    String stage = "CONNECT";
    var raw = RawExecutionLog.sink();
    raw.record(
        "SFTP_CONNECT",
        "SFTP连接请求",
        Map.of(
            "host",
            c.get("host"),
            "port",
            c.get("port"),
            "username",
            str(c, "username", ""),
            "authType",
            str(c, "authType", "PASSWORD")));
    var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
    try (var client = SshClient.setUpDefaultClient()) {
      client.addSessionListener(
          new org.apache.sshd.common.session.SessionListener() {
            @Override
            public void sessionEvent(
                org.apache.sshd.common.session.Session session,
                org.apache.sshd.common.session.SessionListener.Event event) {
              raw.record(
                  "SSH_SESSION",
                  "SSH会话事件",
                  Map.of("event", event.name(), "session", session.toString()));
            }

            @Override
            public void sessionException(
                org.apache.sshd.common.session.Session session, Throwable failure) {
              raw.exception(failure);
            }

            @Override
            public void sessionDisconnect(
                org.apache.sshd.common.session.Session session,
                int reason,
                String message,
                String language,
                boolean initiator) {
              raw.record(
                  "SSH_DISCONNECT",
                  "SSH断开连接",
                  Map.of(
                      "reason",
                      reason,
                      "message",
                      Objects.toString(message, ""),
                      "language",
                      Objects.toString(language, ""),
                      "initiator",
                      initiator));
            }

            @Override
            public void sessionClosed(org.apache.sshd.common.session.Session session) {
              raw.record("SSH_SESSION", "SSH会话关闭", Map.of("session", session.toString()));
            }
          });
      client.setServerKeyVerifier((session, address, key) -> true);
      org.apache.sshd.sftp.client.impl.AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT.set(
          client, Duration.ofSeconds(((Number) c.get("readTimeout")).longValue()));
      org.apache.sshd.core.CoreModuleProperties.IDLE_TIMEOUT.set(
          client, Duration.ofSeconds(((Number) c.get("readTimeout")).longValue()));
      org.apache.sshd.core.CoreModuleProperties.NIO2_READ_TIMEOUT.set(
          client, Duration.ofSeconds(((Number) c.get("readTimeout")).longValue()));
      client.start();
      try (var session =
              client
                  .connect(
                      str(c, "username", ""),
                      str(c, "host", ""),
                      Integer.parseInt(str(c, "port", "22")))
                  .verify(Duration.ofSeconds(((Number) c.get("connectTimeout")).longValue()))
                  .getSession();
          var deadline =
              new Deadline(
                  () -> {
                    timedOut.set(true);
                    session.close(true);
                  },
                  ((Number) c.get("readTimeout")).longValue())) {
        stage = "KEY_LOADING";
        raw.record(
            "SFTP_CONNECT",
            "SFTP连接成功",
            Map.of("remoteAddress", session.getIoSession().getRemoteAddress().toString()));
        if ("KEY".equals(c.get("authType"))) {
          var keys =
              org.apache.sshd.common.util.security.SecurityUtils.loadKeyPairIdentities(
                  session,
                  org.apache.sshd.common.NamedResource.ofName("configured-key"),
                  new ByteArrayInputStream(
                      str(c, "privateKey", "").getBytes(StandardCharsets.UTF_8)),
                  org.apache.sshd.common.config.keys.FilePasswordProvider.of(
                      str(c, "passphrase", "")));
          for (var key : keys) session.addPublicKeyIdentity(key);
        } else session.addPasswordIdentity(str(c, "password", ""));
        stage = "AUTH";
        raw.record(
            "SFTP_AUTH",
            "SFTP认证开始",
            Map.of("username", str(c, "username", ""), "authType", str(c, "authType", "PASSWORD")));
        session.auth().verify(Duration.ofSeconds(((Number) c.get("readTimeout")).longValue()));
        raw.record("SFTP_AUTH", "SFTP认证成功", Map.of("authenticated", session.isAuthenticated()));
        stage = "SFTP_SESSION";
        try (var ftp = SftpClientFactory.instance().createSftpClient(session)) {
          if (test) return new SftpResult(Map.of("connected", true), null);
          stage = "UPLOAD";
          String operation = str(step, "operation", "UPLOAD");
          raw.record(
              "SFTP_REQUEST",
              "SFTP操作请求",
              Map.of(
                  "operation",
                  operation,
                  "config",
                  step,
                  "filename",
                  Objects.toString(filename, ""),
                  "bodyBase64",
                  data == null ? new byte[0] : data,
                  "bytes",
                  data == null ? 0 : data.length));
          Errors.require(Set.of("UPLOAD", "MOVE", "READ").contains(operation), "SFTP操作类型不合法");
          if (operation.equals("READ")) {
            stage = "READ";
            String path = str(step, "sourcePath", "");
            Errors.require(
                path.startsWith("/")
                    && !path.endsWith("/")
                    && !path.contains("\\")
                    && !path.codePoints().anyMatch(Character::isISOControl)
                    && !Arrays.asList(path.split("/")).contains(".."),
                "请填写有效的远端绝对文件路径");
            String originalName = path.substring(path.lastIndexOf('/') + 1);
            Errors.require(!originalName.equals(".") && originalName.length() <= 200, "文件名不合法");
            var attributes = ftp.lstat(path);
            raw.record(
                "SFTP_STAT",
                "SFTP文件属性",
                Map.of(
                    "path",
                    path,
                    "size",
                    attributes.getSize(),
                    "permissions",
                    attributes.getPermissions(),
                    "regularFile",
                    attributes.isRegularFile()));
            if (!attributes.isRegularFile())
              throw new RemoteFailure("NOT_REGULAR_FILE", stage, "读取路径必须为普通文件");
            if (attributes.getSize() > maxBytes)
              throw new RemoteFailure("FILE_TOO_LARGE", stage, "远端文件超过当前流程允许的读取大小");
            byte[] bytes;
            try (var input = ftp.read(path)) {
              bytes =
                  readCaptured(
                      input,
                      maxBytes + 1,
                      (captured, complete) ->
                          raw.record(
                              "SFTP_RESPONSE",
                              "SFTP读取响应",
                              Map.of(
                                  "path",
                                  path,
                                  "bodyBase64",
                                  captured,
                                  "bytes",
                                  captured.length,
                                  "complete",
                                  complete && captured.length <= maxBytes)));
            }
            if (bytes.length > maxBytes) {
              Arrays.fill(bytes, (byte) 0);
              throw new RemoteFailure("FILE_TOO_LARGE", stage, "远端文件超过当前流程允许的读取大小");
            }
            return new SftpResult(Map.of(), new RemoteFile(bytes, originalName, path));
          }
          if (operation.equals("MOVE")) {
            String from = str(step, "sourcePath", ""), to = str(step, "targetPath", "");
            for (String path : List.of(from, to))
              Errors.require(
                  path.startsWith("/")
                      && !path.endsWith("/")
                      && !path.contains("\\")
                      && !path.codePoints().anyMatch(Character::isISOControl)
                      && !Arrays.asList(path.split("/")).contains(".."),
                  "源和目标必须为有效的远端绝对文件路径");
            if (Boolean.TRUE.equals(step.get("appendTimestamp"))) {
              // 执行时生成北京时间后缀，保留完整文件名和扩展名。
              to +=
                  "_"
                      + java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                          .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            }
            Errors.require(!from.equals(to), "源路径和目标路径不能相同");
            Errors.require(ftp.lstat(from).isRegularFile(), "源路径必须为文件");
            raw.record(
                "SFTP_COMMAND",
                "SFTP移动文件",
                Map.of(
                    "sourcePath",
                    from,
                    "targetPath",
                    to,
                    "overwrite",
                    Boolean.TRUE.equals(step.get("overwrite"))));
            if (Boolean.TRUE.equals(step.get("overwrite")))
              ftp.rename(from, to, SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite);
            else {
              try {
                ftp.lstat(to);
                throw new IOException("目标文件已存在");
              } catch (org.apache.sshd.sftp.common.SftpException missing) {
                if (missing.getStatus() != 2) throw missing;
              }
              ftp.rename(from, to);
            }
            raw.record(
                "SFTP_RESPONSE",
                "SFTP移动成功",
                Map.of("sourcePath", from, "targetPath", to, "success", true));
            return new SftpResult(
                Map.of(
                    "moved",
                    true,
                    "sourcePath",
                    from,
                    "targetPath",
                    to,
                    "message",
                    "SFTP移动/重命名成功：" + from + " -> " + to),
                null);
          }
          Errors.require(data != null && data.length <= 16777216, "文件大小不合法");
          Errors.require(
              filename != null && filename.matches("[^/\\\\\r\n]+") && !filename.equals(".."),
              "文件名不合法");
          String dir = str(step, "remoteDirectory", "");
          Errors.require(
              dir.startsWith("/") && !Arrays.asList(dir.split("/")).contains(".."), "目录不合法");
          String target = dir.replaceAll("/+$", "") + "/" + filename,
              tmp = target + "." + UUID.randomUUID() + ".tmp";
          raw.record(
              "SFTP_COMMAND",
              "SFTP写入临时文件",
              Map.of("temporaryPath", tmp, "targetPath", target, "bytes", data.length));
          try {
            try (var out =
                ftp.write(
                    tmp,
                    SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Exclusive)) {
              out.write(data);
            }
            raw.record(
                "SFTP_COMMAND",
                "SFTP提交临时文件",
                Map.of(
                    "sourcePath",
                    tmp,
                    "targetPath",
                    target,
                    "overwrite",
                    Boolean.TRUE.equals(step.get("overwrite"))));
            if (Boolean.TRUE.equals(step.get("overwrite")))
              ftp.rename(tmp, target, SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite);
            else {
              try {
                ftp.lstat(target);
                throw new IOException("目标文件已存在");
              } catch (org.apache.sshd.sftp.common.SftpException missing) {
                if (missing.getStatus()
                    != org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE) throw missing;
              }
              ftp.rename(tmp, target);
            }
            raw.record(
                "SFTP_RESPONSE",
                "SFTP上传成功",
                Map.of("path", target, "bytes", data.length, "success", true));
            return new SftpResult(Map.of("bytes", data.length, "uploaded", true), null);
          } finally {
            try {
              ftp.remove(tmp);
              raw.record("SFTP_CLEANUP", "SFTP临时文件清理成功", Map.of("path", tmp));
            } catch (Exception ignored) {
              raw.record(
                  "SFTP_CLEANUP",
                  "SFTP临时文件清理结果",
                  Map.of(
                      "path",
                      tmp,
                      "exceptionType",
                      ignored.getClass().getName(),
                      "message",
                      Objects.toString(ignored.getMessage(), "")));
            }
          }
        }
      }
    } catch (Exception failure) {
      RawExecutionLog.exception(failure);
      if (timedOut.get()) throw new RemoteFailure("TIMEOUT", stage, "SFTP认证或读写超时：请检查服务器响应和超时设置");
      throw RemoteFailure.of("SFTP", stage, c, failure);
    }
  }

  /**
   * @description 执行HTTP请求并按通用或固定业务契约判定结果
   * @author Freddy
   * @date 2026-11-12
   * @param c 组件配置
   * @param step 执行步骤快照
   * @param data 待发送文件字节，可为空
   * @param filename 安全文件名
   * @param test 是否仅测试连接
   * @return 无效配置时抛出异常，否则返回处理结果
   * @throws Exception 远端操作、证书解析或文件处理失败
   */
  Map<String, Object> http(
      Map<String, Object> c, Map<String, Object> step, byte[] data, String filename, boolean test)
      throws Exception {
    return http(c, step, data, filename, test, false);
  }

  /**
   * @description 在内存中读取业务HTTP文件响应，沿用服务器鉴权和大小限制
   * @author Freddy
   * @date 2026-11-13
   * @param serverId 来源服务器
   * @param configId 冻结连接版本
   * @param step HTTP读取配置
   * @param maxBytes 允许解码的最大字节数
   * @return 原始文件和响应路径；原文另存加密审计详情
   * @throws Exception 响应无效或远端失败
   */
  public RemoteFile readHttpFile(
      long serverId, long configId, Map<String, Object> step, int maxBytes) throws Exception {
    try (var budget = RawExecutionLog.reserveRemoteCapacity()) {
      Errors.require(servers.enabled(serverId), "来源服务器已停用");
      var result = http(versionedConnection(configId), step, null, "", false, true);
      byte[] bytes = (byte[]) result.get("fileContent");
      Errors.require(bytes.length <= maxBytes, "文件超过流程大小限制");
      String path = result.get("path").toString();
      return new RemoteFile(bytes, path.substring(path.lastIndexOf('/') + 1), path);
    }
  }

  /**
   * @description 区分普通请求与仅在内存返回文件内容的业务读取
   * @author Freddy
   * @date 2026-11-13
   * @param c 服务器连接
   * @param step 请求配置
   * @param data 请求文件
   * @param filename 文件名
   * @param test 是否连通测试
   * @param readFile 是否读取业务文件
   * @return 请求结果或仅供调用方消费的文件数据
   * @throws Exception 请求或响应校验失败
   */
  private Map<String, Object> http(
      Map<String, Object> c,
      Map<String, Object> step,
      byte[] data,
      String filename,
      boolean test,
      boolean readFile)
      throws Exception {
    String path = str(test ? c : step, test ? "testPath" : "path", "/");
    Errors.require(
        path.startsWith("/")
            && !path.startsWith("//")
            && !path.contains("\\")
            && !Arrays.asList(path.split("/")).contains(".."),
        "相对路径不合法");
    URI base = URI.create(str(c, "baseUrl", ""));
    String prefix = Objects.toString(base.getRawPath(), "").replaceAll("/+$", "");
    safePath(prefix);
    safePath(path);
    URI uri = URI.create(base.getScheme() + "://" + base.getRawAuthority() + prefix + path);
    Errors.require(uri.getHost().equals(base.getHost()) && uri.getFragment() == null, "请求目标不合法");
    if (!test && step.get("query") instanceof Map<?, ?> query && !query.isEmpty()) {
      StringJoiner join = new StringJoiner("&");
      for (var entry : query.entrySet())
        join.add(
            URLEncoder.encode(entry.getKey().toString(), StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
      uri = URI.create(uri.toString() + (uri.getQuery() == null ? "?" : "&") + join);
    }
    String method = str(test ? c : step, test ? "testMethod" : "method", test ? "HEAD" : "POST");
    Errors.require(
        (test
                ? List.of("HEAD", "GET")
                : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
            .contains(method),
        "方法不合法");
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(((Number) c.get("readTimeout")).longValue()));
    for (var source :
        List.of(
            c.getOrDefault("headers", Map.of()),
            c.getOrDefault("secretHeaders", Map.of()),
            step.getOrDefault("headers", Map.of()))) {
      for (var e : ((Map<?, ?>) source).entrySet()) {
        String name = e.getKey().toString();
        Errors.require(
            !Set.of(
                    "host",
                    "content-length",
                    "connection",
                    "transfer-encoding",
                    "authorization",
                    "cookie",
                    "proxy-authorization")
                .contains(name.toLowerCase(Locale.ROOT)),
            "请求头不合法");
        builder.header(name, e.getValue().toString());
      }
    }
    String auth = str(c, "authType", "NONE");
    if (auth.equals("BASIC"))
      builder.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (str(c, "username", "") + ":" + str(c, "password", ""))
                          .getBytes(StandardCharsets.UTF_8)));
    if (auth.equals("BEARER")) builder.header("Authorization", "Bearer " + str(c, "token", ""));
    byte[] body = new byte[0];
    if (!test) {
      String mode =
          str(
              step,
              "bodyMode",
              Set.of("GET", "HEAD", "OPTIONS").contains(method) ? "NONE" : "BINARY");
      Errors.require(
          !Set.of("GET", "HEAD").contains(method) || mode.equals("NONE"), "GET/HEAD请求不允许请求体");
      if (mode.equals("NONE")) {
        body = new byte[0];
      } else if (mode.equals("FORM")) {
        var form = new StringJoiner("&");
        for (var entry : ((Map<?, ?>) step.getOrDefault("fields", Map.of())).entrySet())
          form.add(
              URLEncoder.encode(entry.getKey().toString(), StandardCharsets.UTF_8)
                  + "="
                  + URLEncoder.encode(
                      Objects.toString(entry.getValue(), ""), StandardCharsets.UTF_8));
        body = form.toString().getBytes(StandardCharsets.UTF_8);
        builder.header("Content-Type", "application/x-www-form-urlencoded");
      } else if (mode.equals("TEXT")) {
        body = str(step, "body", "").getBytes(StandardCharsets.UTF_8);
        builder.header("Content-Type", str(step, "contentType", "text/plain; charset=UTF-8"));
      } else if (mode.equals("JSON")) {
        String text = str(step, "body", "{}");
        // 固定业务报文已在入队时冻结，客户标识中的字面占位符必须原样发送。
        if (str(step, "httpComponent", "GENERIC").equals("GENERIC")) {
          text =
              text.replace(
                      "${fileBase64}", data == null ? "" : Base64.getEncoder().encodeToString(data))
                  .replace(
                      "${certificateBase64}",
                      data == null ? "" : Base64.getEncoder().encodeToString(data));
        }
        json.readTree(text);
        body = text.getBytes(StandardCharsets.UTF_8);
        builder.header("Content-Type", "application/json");
      } else if (mode.equals("MULTIPART")) {
        Errors.require(data != null, "缺少文件");
        String boundary = "cert" + UUID.randomUUID();
        var out = new ByteArrayOutputStream();
        for (var entry : ((Map<?, ?>) step.getOrDefault("fields", Map.of())).entrySet()) {
          String key = entry.getKey().toString();
          Errors.require(key.matches("[A-Za-z0-9_-]+"), "字段名不合法");
          out.write(
              ("--"
                      + boundary
                      + "\r\nContent-Disposition: form-data; name=\""
                      + key
                      + "\"\r\n\r\n"
                      + entry.getValue()
                      + "\r\n")
                  .getBytes(StandardCharsets.UTF_8));
        }
        String field = str(step, "fileField", "file");
        Errors.require(
            field.matches("[A-Za-z0-9_-]+")
                && filename != null
                && filename.matches("[^\\\"\\\\\\r\\n/]+"),
            "文件字段不合法");
        out.write(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + field
                    + "\"; filename=\""
                    + filename
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        body = out.toByteArray();
        builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
      } else {
        Errors.require(mode.equals("BINARY") && data != null, "请求模式不合法");
        body = data;
        builder.header("Content-Type", "application/octet-stream");
      }
    }
    Errors.require(body.length <= 16777216, "请求过大");
    builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    try (var lease = acquireClient(c)) {
      var client = lease.entry.client;
      var request = builder.build();
      var raw = RawExecutionLog.sink();
      raw.record(
          "HTTP_REQUEST",
          "HTTP请求",
          Map.of(
              "url",
              request.uri().toString(),
              "method",
              request.method(),
              "headers",
              request.headers().map(),
              "bodyBase64",
              body,
              "bytes",
              body.length));
      long sendStarted = System.nanoTime();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      sendNanos.add(System.nanoTime() - sendStarted);
      long bodyStarted = System.nanoTime();
      byte[] bytes;
      try (var in = response.body();
          var deadline =
              new Deadline(
                  () -> {
                    try {
                      in.close();
                    } catch (IOException ignored) {
                    }
                  },
                  ((Number) c.get("readTimeout")).longValue())) {
        try {
          int responseLimit = readFile ? 14 * 1024 * 1024 : 1048576;
          bytes =
              readCaptured(
                  in,
                  responseLimit + 1,
                  (captured, complete) ->
                      raw.record(
                          "HTTP_RESPONSE",
                          "HTTP响应",
                          Map.of(
                              "url",
                              response.uri().toString(),
                              "statusCode",
                              response.statusCode(),
                              "version",
                              response.version().toString(),
                              "headers",
                              response.headers().map(),
                              "bodyBase64",
                              captured,
                              "bytes",
                              captured.length,
                              "complete",
                              complete && captured.length <= responseLimit)));
        } catch (IOException failure) {
          RawExecutionLog.exception(failure);
          if (deadline.expired)
            throw new RemoteFailure("TIMEOUT", "HTTP_RESPONSE", "HTTP响应内容读取超时：请检查接口响应和超时设置");
          throw failure;
        }
      }
      responseNanos.add(System.nanoTime() - bodyStarted);
      Errors.require(bytes.length <= (readFile ? 14 * 1024 * 1024 : 1048576), "响应过大");
      Object codes = (test ? c : step).get(test ? "testCodes" : "successCodes");
      boolean success =
          codes instanceof Collection<?> cs && !cs.isEmpty()
              ? cs.stream()
                  .anyMatch(v -> v.toString().equals(String.valueOf(response.statusCode())))
              : response.statusCode() >= 200 && response.statusCode() < 300;
      if (!success) {
        String diagnostic = "HTTP响应状态码为 " + response.statusCode() + "，不符合配置的成功状态码";
        if (!test
            && !Set.of("GENERIC", "BUSINESS_UPDATE")
                .contains(str(step, "httpComponent", "GENERIC"))) {
          try {
            var failureNode = json.readTree(bytes);
            if (failureNode != null
                && failureNode.isObject()
                && failureNode.path("message").isTextual())
              diagnostic += "：" + safeBusinessMessage(failureNode.path("message").asText(), c);
          } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
            // 非JSON错误响应保留状态码诊断，不将原始响应写入执行日志。
          }
        }
        throw new RemoteFailure("HTTP_STATUS", "HTTP_RESPONSE", diagnostic);
      }
      if (readFile) {
        var node = json.readTree(bytes);
        Errors.require(
            node != null && node.isObject() && node.path("code").asText().equals("0"),
            "读取接口业务失败或缺少code");
        Errors.require(
            node.path("path").isTextual() && !node.path("path").asText().isBlank(), "读取接口缺少path");
        Errors.require(
            node.path("content").isTextual() && !node.path("content").asText().isBlank(),
            "读取接口缺少content");
        byte[] content = Base64.getDecoder().decode(node.path("content").asText());
        Errors.require(content.length <= 10 * 1024 * 1024, "文件超过10MiB");
        return Map.of("path", node.path("path").asText(), "fileContent", content);
      }
      if (!test && !str(step, "httpComponent", "GENERIC").equals("GENERIC"))
        return businessResult(step, c, bytes, response.statusCode(), body.length);
      String pathCheck = str(step, "jsonPath", "");
      if (!test && !pathCheck.isBlank()) {
        Errors.require(pathCheck.matches("[A-Za-z0-9_.]+"), "JSON路径不合法");
        var node = json.readTree(bytes);
        for (String part : pathCheck.split("\\."))
          node = part.matches("[0-9]+") ? node.path(Integer.parseInt(part)) : node.path(part);
        if (!node.asText().equals(str(step, "expectedValue", "")))
          throw new RemoteFailure("HTTP_RESULT_MISMATCH", "HTTP_RESPONSE", "HTTP返回字段不符合配置的成功判定");
      }
      return Map.of("statusCode", response.statusCode(), "bytes", body.length);
    }
  }

  /**
   * @description 有界读取并在成功或异常时保存实际收到的字节，避免超时丢失已接收内容
   * @author Freddy
   * @date 2026-11-13
   * @param input 远端输入流
   * @param limit 读取上限，保留额外一字节用于超限判定
   * @param capture 实际内容和完整性标记接收器
   * @return 已接收字节
   * @throws IOException 输入流失败
   */
  private static byte[] readCaptured(
      InputStream input, int limit, java.util.function.BiConsumer<byte[], Boolean> capture)
      throws IOException {
    var output = new ByteArrayOutputStream();
    boolean complete = false;
    try {
      var buffer = new byte[8192];
      while (output.size() < limit) {
        int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
        if (count < 0) {
          complete = true;
          break;
        }
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    } finally {
      capture.accept(output.toByteArray(), complete);
    }
  }

  /**
   * @description 按固定接口各自约定判定业务响应，日志仅接收清理后的message
   * @author Freddy
   * @date 2026-11-12
   * @param step 固定请求快照
   * @param config 服务器连接配置
   * @param bytes 响应JSON字节
   * @param status HTTP状态码
   * @param size 请求字节数
   * @return 安全业务结果
   * @throws Exception JSON格式或业务结果不满足接口契约
   */
  Map<String, Object> businessResult(
      Map<String, Object> step, Map<String, Object> config, byte[] bytes, int status, int size)
      throws Exception {
    var node = json.readTree(bytes);
    String component = str(step, "httpComponent", "");
    // 新业务接口发送含私钥的证书库，远端message可能回显文件，使用固定摘要避免持久化回显。
    if (component.equals("BUSINESS_UPDATE")) {
      if (node == null
          || !node.isObject()
          || !(node.path("code").isTextual() || node.path("code").isIntegralNumber()))
        throw new RemoteFailure("HTTP_INVALID_RESULT", "HTTP_RESPONSE", "更新接口缺少有效code");
      if (!node.path("code").asText().equals("0"))
        throw new RemoteFailure("HTTP_BUSINESS_ERROR", "HTTP_RESPONSE", "更新接口返回业务失败，请检查目标系统状态");
      return Map.of("statusCode", status, "bytes", size, "message", "远端更新接口成功");
    }
    if (node == null || !node.isObject() || !node.path("message").isTextual())
      throw new RemoteFailure("HTTP_INVALID_RESULT", "HTTP_RESPONSE", "固定业务接口响应格式不合法");
    String message = safeBusinessMessage(node.path("message").asText(), config);
    boolean success;
    if (component.equals("RELOAD_KEYSTORE")) {
      var flag = node.path("isError");
      if (!(flag.isBoolean() || flag.isTextual())
          || !Set.of("true", "false").contains(flag.asText()))
        throw new RemoteFailure(
            "HTTP_INVALID_RESULT", "HTTP_RESPONSE", "重载接口缺少有效isError判定：" + message);
      success = flag.asText().equals("false");
    } else {
      var code = node.path("code");
      if (!(code.isTextual() || code.isIntegralNumber()))
        throw new RemoteFailure(
            "HTTP_INVALID_RESULT", "HTTP_RESPONSE", "TN接口缺少有效code判定：" + message);
      success = code.asText().equals("0");
    }
    if (!success) throw new RemoteFailure("HTTP_BUSINESS_ERROR", "HTTP_RESPONSE", message);
    return Map.of("statusCode", status, "bytes", size, "message", message);
  }

  /**
   * @description 清除业务响应控制字符、长编码及配置秘密并限制日志长度
   * @author Freddy
   * @date 2026-11-12
   * @param message 远端业务消息
   * @param config 服务器连接配置
   * @return 可写入执行日志的业务消息
   */
  static String safeBusinessMessage(String message, Map<String, Object> config) {
    String safe = message;
    for (String key : List.of("password", "token", "privateKey", "passphrase")) {
      String secret = str(config, key, "");
      if (!secret.isEmpty()) safe = safe.replace(secret, "[已隐藏]");
    }
    safe = redactSecretValues(safe, config.get("secretHeaders"));
    safe = safe.replaceAll("[\\p{Cntrl}]", " ");
    safe = safe.replaceAll("[A-Za-z0-9+/=]{80,}", "[已隐藏]");
    return safe.substring(0, Math.min(safe.length(), 500));
  }

  /**
   * @description 递归隐藏秘密请求头中的对象、集合及文本值，避免远端回显泄漏
   * @author Freddy
   * @date 2026-11-12
   * @param message 待清理的业务消息
   * @param secrets 秘密请求头值及其嵌套配置，可为空
   * @return 替换秘密值后的消息
   */
  static String redactSecretValues(String message, Object secrets) {
    String safe = message;
    if (secrets instanceof Map<?, ?> values) {
      for (Object value : values.values()) safe = redactSecretValues(safe, value);
    } else if (secrets instanceof Collection<?> values) {
      for (Object value : values) safe = redactSecretValues(safe, value);
    } else if (secrets != null && !secrets.toString().isEmpty()) {
      safe = safe.replace(secrets.toString(), "[已隐藏]");
    }
    return safe;
  }

  /**
   * @description 建立系统及配置证书共同信任的TLS上下文
   * @author Freddy
   * @date 2026-11-12
   * @param c 组件配置
   * @return 无效配置时抛出异常，否则返回处理结果
   * @throws Exception 远端操作、证书解析或文件处理失败
   */
  javax.net.ssl.SSLContext tls(Map<String, Object> c) throws Exception {
    var context = javax.net.ssl.SSLContext.getInstance("TLS");
    var defaults =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    defaults.init((java.security.KeyStore) null);
    var store = java.security.KeyStore.getInstance("JKS");
    store.load(null, null);
    int index = 0;
    for (byte[] der : trustDer(c)) {
      var cert =
          (java.security.cert.X509Certificate)
              java.security.cert.CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(der));
      if (cert.getBasicConstraints() >= 0) store.setCertificateEntry("ca" + (index++), cert);
    }
    var extra =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    extra.init(store);
    var d = (javax.net.ssl.X509TrustManager) defaults.getTrustManagers()[0];
    var x = (javax.net.ssl.X509TrustManager) extra.getTrustManagers()[0];
    context.init(
        null,
        new javax.net.ssl.TrustManager[] {
          new javax.net.ssl.X509TrustManager() {
            /**
             * @description 返回系统认可的证书颁发者
             * @author Freddy
             * @date 2026-11-12
             * @return 无效配置时抛出异常，否则返回处理结果
             */
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
              return d.getAcceptedIssuers();
            }

            /**
             * @description 使用系统信任库验证客户端链
             * @author Freddy
             * @date 2026-11-12
             * @param chain 操作所需的配置或依赖
             * @param auth 操作所需的配置或依赖
             * @throws java.security.cert.CertificateException 远端操作、证书解析或文件处理失败
             */
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth)
                throws java.security.cert.CertificateException {
              d.checkClientTrusted(chain, auth);
            }

            /**
             * @description 依次使用系统及额外信任库验证服务端链
             * @author Freddy
             * @date 2026-11-12
             * @param chain 操作所需的配置或依赖
             * @param auth 操作所需的配置或依赖
             * @throws java.security.cert.CertificateException 远端操作、证书解析或文件处理失败
             */
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth)
                throws java.security.cert.CertificateException {
              try {
                d.checkServerTrusted(chain, auth);
              } catch (java.security.cert.CertificateException e) {
                x.checkServerTrusted(chain, auth);
              }
            }
          }
        },
        null);
    return context;
  }

  /**
   * @description 拒绝路径穿越及多重编码绕过
   * @author Freddy
   * @date 2026-11-12
   * @param raw 操作所需的配置或依赖
   */
  static void safePath(String raw) {
    Errors.require(!raw.contains("#") && !raw.contains("?") && !raw.startsWith("//"), "路径不合法");
    String decoded = raw;
    for (int count = 0; count < 4; count++) {
      String next = java.net.URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8);
      Errors.require(
          !next.contains("\\")
              && !next.contains("\r")
              && !next.contains("\n")
              && !next.startsWith("//")
              && !Arrays.asList(next.split("/", -1)).contains("..")
              && !Arrays.asList(next.split("/", -1)).contains("."),
          "路径不合法");
      if (next.equals(decoded)) return;
      decoded = next;
    }
    Errors.require(!decoded.contains("%"), "路径编码不合法");
  }

  private final LinkedHashMap<String, ClientEntry> clients = new LinkedHashMap<>(16, 0.75f, true);
  private final java.util.concurrent.atomic.LongAdder
      created = new java.util.concurrent.atomic.LongAdder(),
      reused = new java.util.concurrent.atomic.LongAdder(),
      sendNanos = new java.util.concurrent.atomic.LongAdder(),
      responseNanos = new java.util.concurrent.atomic.LongAdder();
  private boolean stopping;
  private static final int MAX_CLIENTS = 64;

  /**
   * @description 保存共享客户端及在途引用，退休后等待最后租约关闭
   * @author Freddy
   * @date 2026-11-16
   */
  private static final class ClientEntry {
    final HttpClient client;
    int users;
    boolean retired;

    /**
     * @description 保存客户端所有权供租约共享
     * @author Freddy
     * @date 2026-11-16
     * @param client 新建客户端
     */
    ClientEntry(HttpClient client) {
      this.client = client;
    }
  }

  /**
   * @description 在响应体关闭前持有客户端引用，防止LRU提前关闭在途连接
   * @author Freddy
   * @date 2026-11-16
   */
  private final class ClientLease implements AutoCloseable {
    final ClientEntry entry;

    /**
     * @description 持有已在缓存锁内增加的引用
     * @author Freddy
     * @date 2026-11-16
     * @param entry 缓存条目
     */
    ClientLease(ClientEntry entry) {
      this.entry = entry;
    }

    /**
     * @description 归还引用，服务关闭时仅由最后使用者关闭客户端
     * @author Freddy
     * @date 2026-11-16
     */
    public void close() {
      synchronized (clients) {
        entry.users--;
        if (entry.retired && entry.users == 0) entry.client.close();
      }
    }
  }

  /**
   * @description 每次读取当前可用配置，再附加不可变版本键，避免复用掩盖停用检查
   * @author Freddy
   * @date 2026-11-16
   * @param id 配置版本
   * @return 独立连接快照
   */
  private Map<String, Object> versionedConnection(long id) {
    var copy = new HashMap<>(servers.connection(id));
    copy.put("_clientConfigId", id);
    return copy;
  }

  /**
   * @description 读取本次信任证书字节，缓存键包含材料版本且TLS解析只发生在新建客户端时
   * @author Freddy
   * @date 2026-11-16
   * @param config 连接快照
   * @return 本次材料快照
   */
  @SuppressWarnings("unchecked")
  private List<byte[]> trustDer(Map<String, Object> config) {
    if (config.containsKey("_clientTrustDer")) return (List<byte[]>) config.get("_clientTrustDer");
    var result = new ArrayList<byte[]>();
    for (Object id : (Collection<?>) config.getOrDefault("_trustMaterialIds", List.of()))
      for (var row :
          servers.db.queryForList(
              "select certificate_der from certificate_materials where id=?", id))
        result.add((byte[]) row.get("CERTIFICATE_DER"));
    return result;
  }

  /**
   * @description 有界借用HTTP客户端；仅淘汰空闲条目，全部使用中时显式背压
   * @author Freddy
   * @date 2026-11-16
   * @param config 当前已校验连接快照
   * @return 引用计数租约
   * @throws Exception TLS配置失败或缓存已关闭
   */
  private ClientLease acquireClient(Map<String, Object> config) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    var materials = trustDer(config);
    for (byte[] der : materials) digest.update(der);
    String key =
        Objects.toString(config.get("_clientConfigId"), "")
            + "/"
            + config.get("baseUrl")
            + "/"
            + config.get("connectTimeout")
            + "/"
            + HexFormat.of().formatHex(digest.digest());
    synchronized (clients) {
      if (stopping) throw new IllegalStateException("远程HTTP客户端已关闭");
      var entry = clients.get(key);
      if (entry != null) {
        reused.increment();
        entry.users++;
        return new ClientLease(entry);
      }
      if (clients.size() >= MAX_CLIENTS) {
        var iterator = clients.entrySet().iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
          var candidate = iterator.next().getValue();
          if (candidate.users == 0) {
            iterator.remove();
            candidate.client.close();
            removed = true;
            break;
          }
        }
        if (!removed) throw new IllegalStateException("远程HTTP客户端并发达到上限");
      }
      var snapshot = new HashMap<>(config);
      snapshot.put("_clientTrustDer", materials);
      entry =
          new ClientEntry(
              HttpClient.newBuilder()
                  .sslContext(tls(snapshot))
                  .connectTimeout(
                      Duration.ofSeconds(((Number) config.get("connectTimeout")).longValue()))
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .build());
      entry.users = 1;
      clients.put(key, entry);
      created.increment();
      return new ClientLease(entry);
    }
  }

  /**
   * @description 关闭空闲客户端，在途请求归还租约后关闭，避免中断其他请求
   * @author Freddy
   * @date 2026-11-16
   */
  @jakarta.annotation.PreDestroy
  public void stop() {
    synchronized (clients) {
      stopping = true;
      for (var entry : clients.values()) {
        entry.retired = true;
        if (entry.users == 0) entry.client.close();
      }
      clients.clear();
    }
  }

  /**
   * @description 返回远端性能分组名称
   * @author Freddy
   * @date 2026-11-16
   * @return 固定分组
   */
  public String performanceName() {
    return "remoteTransport";
  }

  /**
   * @description 返回不包含目标或正文的客户端复用和HTTP阶段累计耗时
   * @author Freddy
   * @date 2026-11-16
   * @return 聚合指标
   */
  public Map<String, Object> performanceSnapshot() {
    synchronized (clients) {
      return Map.of(
          "cachedClients",
          clients.size(),
          "activeLeases",
          clients.values().stream().mapToInt(e -> e.users).sum(),
          "maxClients",
          MAX_CLIENTS,
          "clientsCreated",
          created.sum(),
          "clientsReused",
          reused.sum(),
          "httpSendNanos",
          sendNanos.sum(),
          "httpResponseNanos",
          responseNanos.sum());
    }
  }
}
