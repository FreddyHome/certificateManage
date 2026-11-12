package com.certmanage.server;

import com.certmanage.common.Errors;
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
 * @description 执行远端HTTP与SFTP调用并提供安全诊断
 * @author Freddy
 * @date 2026-11-12
 */
public class RemoteTransport {
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
    Errors.require(servers.enabled(serverId), "服务器已停用");
    var c = servers.connection(configId);
    try {
      return "SFTP".equals(c.get("type"))
          ? sftp(c, step, data, filename, false)
          : http(c, step, data, filename, false);
    } catch (Exception e) {
      throw RemoteFailure.of(str(c, "type", "REMOTE"), "REMOTE_CALL", c, e);
    }
  }

  /**
   * @description 测试服务器连接并返回安全诊断
   * @author Freddy
   * @date 2026-11-12
   * @param id 记录标识
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  public Map<String, Object> test(long id) {
    var snap = servers.snapshot(id);
    var c = servers.connection(((Number) snap.get("configId")).longValue());
    long begin = System.nanoTime();
    try {
      var result =
          "SFTP".equals(c.get("type"))
              ? sftp(c, Map.of(), null, null, true)
              : http(c, Map.of(), null, null, true);
      return Map.of(
          "success", true, "elapsedMs", (System.nanoTime() - begin) / 1000000, "result", result);
    } catch (Exception e) {
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

  /**
   * @description 执行SFTP连接测试或文件操作
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
  Map<String, Object> sftp(
      Map<String, Object> c, Map<String, Object> step, byte[] data, String filename, boolean test)
      throws Exception {
    String stage = "CONNECT";
    var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
    try (var client = SshClient.setUpDefaultClient()) {
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
        session.auth().verify(Duration.ofSeconds(((Number) c.get("readTimeout")).longValue()));
        stage = "SFTP_SESSION";
        try (var ftp = SftpClientFactory.instance().createSftpClient(session)) {
          if (test) return Map.of("connected", true);
          stage = "UPLOAD";
          String operation = str(step, "operation", "UPLOAD");
          Errors.require(Set.of("UPLOAD", "MOVE").contains(operation), "SFTP操作类型不合法");
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
            return Map.of(
                "moved",
                true,
                "sourcePath",
                from,
                "targetPath",
                to,
                "message",
                "SFTP移动/重命名成功：" + from + " -> " + to);
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
          try {
            try (var out =
                ftp.write(
                    tmp,
                    SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Exclusive)) {
              out.write(data);
            }
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
            return Map.of("bytes", data.length, "uploaded", true);
          } finally {
            try {
              ftp.remove(tmp);
            } catch (Exception ignored) {
            }
          }
        }
      }
    } catch (Exception failure) {
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
    try (var client =
        HttpClient.newBuilder()
            .sslContext(tls(c))
            .connectTimeout(Duration.ofSeconds(((Number) c.get("connectTimeout")).longValue()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
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
          bytes = in.readNBytes(1048577);
        } catch (IOException failure) {
          if (deadline.expired)
            throw new RemoteFailure("TIMEOUT", "HTTP_RESPONSE", "HTTP响应内容读取超时：请检查接口响应和超时设置");
          throw failure;
        }
      }
      Errors.require(bytes.length <= 1048576, "响应过大");
      Object codes = (test ? c : step).get(test ? "testCodes" : "successCodes");
      boolean success =
          codes instanceof Collection<?> cs && !cs.isEmpty()
              ? cs.stream()
                  .anyMatch(v -> v.toString().equals(String.valueOf(response.statusCode())))
              : response.statusCode() >= 200 && response.statusCode() < 300;
      if (!success) {
        String diagnostic = "HTTP响应状态码为 " + response.statusCode() + "，不符合配置的成功状态码";
        if (!test && !str(step, "httpComponent", "GENERIC").equals("GENERIC")) {
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
    for (Object material : (Collection<?>) c.getOrDefault("_trustMaterialIds", List.of())) {
      for (var row :
          servers.db.queryForList(
              "select certificate_der from certificate_materials where id=?", material)) {
        var cert =
            (java.security.cert.X509Certificate)
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(
                        new ByteArrayInputStream((byte[]) row.get("CERTIFICATE_DER")));
        if (cert.getBasicConstraints() >= 0) store.setCertificateEntry("ca" + (index++), cert);
      }
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
}
