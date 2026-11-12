package com.certmanage.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @description 验证SFTP重命名时间戳附加在完整文件名之后且保持默认重命名行为
 * @author Freddy
 * @date 2026-11-12
 */
class SftpTimestampTest {
  @TempDir Path folder;

  /**
   * @description 使用真实SFTP验证同名备份、扩展名后缀、执行时刻、日志结果及关闭选项
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception 本地SFTP服务或文件操作失败时抛出
   */
  @Test
  void timestampFollowsWholeFilenameAtExecutionTime() throws Exception {
    var target = SshServer.setUpDefaultServer();
    target.setPort(0);
    target.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(folder.resolve("hostkey")));
    target.setPasswordAuthenticator(
        (user, password, session) -> user.equals("user") && password.equals("secret"));
    target.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    Path remote = Files.createDirectory(folder.resolve("remote"));
    target.setFileSystemFactory(new VirtualFileSystemFactory(remote));
    target.start();
    try {
      var servers = mock(ServerService.class);
      when(servers.enabled(1)).thenReturn(true);
      when(servers.connection(1))
          .thenReturn(
              new HashMap<>(
                  Map.of(
                      "type",
                      "SFTP",
                      "host",
                      "127.0.0.1",
                      "port",
                      target.getPort(),
                      "username",
                      "user",
                      "password",
                      "secret",
                      "connectTimeout",
                      3,
                      "readTimeout",
                      3)));
      var transport = new RemoteTransport(servers, new ObjectMapper());
      Files.write(remote.resolve("test.p12"), new byte[] {1, 2});
      var before = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1);
      var result =
          transport.execute(
              1,
              1,
              Map.of(
                  "operation",
                  "MOVE",
                  "sourcePath",
                  "/test.p12",
                  "targetPath",
                  "/test.p12",
                  "appendTimestamp",
                  true),
              null,
              "unused");
      String path = (String) result.get("targetPath");
      assertTrue(path.matches("/test\\.p12_\\d{14}"), path);
      var timestamp =
          LocalDateTime.parse(
              path.substring(path.lastIndexOf('_') + 1),
              DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
      assertFalse(timestamp.isBefore(before));
      assertFalse(timestamp.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
      assertFalse(Files.exists(remote.resolve("test.p12")));
      assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(remote.resolve(path.substring(1))));
      assertTrue(result.get("message").toString().contains(path));
      var plain =
          transport.execute(
              1,
              1,
              Map.of(
                  "operation",
                  "MOVE",
                  "sourcePath",
                  path,
                  "targetPath",
                  "/plain.p12",
                  "appendTimestamp",
                  false),
              null,
              "unused");
      assertEquals("/plain.p12", plain.get("targetPath"));
      assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(remote.resolve("plain.p12")));
    } finally {
      target.stop(true);
    }
  }
}
