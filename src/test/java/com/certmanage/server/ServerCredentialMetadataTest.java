package com.certmanage.server;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @description 验证服务端凭据标记不信任客户端，且留空保留与显式清空兼容。
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:p2metadata;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class ServerCredentialMetadataTest {
  @Autowired ServerService servers;

  /**
   * @description 验证凭据标记由真实秘密派生，保留留空与显式清除语义。
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void serverGeneratesFlagsAndPreservesBlankCredentials() {
    var saved =
        servers.save(
            null,
            Map.of(
                "alias",
                "metadata",
                "type",
                "SSH",
                "config",
                Map.of(
                    "host",
                    "example.invalid",
                    "username",
                    "test",
                    "password",
                    "real-secret",
                    "_credentialFlags",
                    Map.of("passwordConfigured", false),
                    "passwordConfigured",
                    false)));
    long id = ((Number) saved.get("id")).longValue();
    assertThat(config(saved))
        .containsEntry("passwordConfigured", true)
        .doesNotContainKeys("password", "_credentialFlags");
    saved =
        servers.save(
            id,
            Map.of(
                "alias",
                "metadata",
                "type",
                "SSH",
                "revision",
                saved.get("revision"),
                "config",
                Map.of(
                    "host",
                    "example.invalid",
                    "username",
                    "test",
                    "password",
                    "",
                    "_credentialFlags",
                    Map.of("passwordConfigured", false))));
    assertThat(config(saved)).containsEntry("passwordConfigured", true);
    var snapshot = servers.snapshot(id);
    assertThat(servers.connection(((Number) snapshot.get("configId")).longValue()))
        .containsEntry("password", "real-secret")
        .doesNotContainKey("_credentialFlags");
    saved =
        servers.save(
            id,
            Map.of(
                "alias",
                "metadata",
                "type",
                "SSH",
                "revision",
                saved.get("revision"),
                "config",
                Map.of(
                    "host",
                    "example.invalid",
                    "username",
                    "test",
                    "clearSecrets",
                    List.of("password"),
                    "_credentialFlags",
                    Map.of("passwordConfigured", true))));
    assertThat(config(saved)).containsEntry("passwordConfigured", false);
  }

  /**
   * @description 提取响应的公开连接配置。
   * @author Freddy
   * @date 2026-11-16
   * @param response 管理响应
   * @return 已去除秘密的配置
   */
  private Map<String, Object> config(Map<String, Object> response) {
    return (Map<String, Object>) response.get("config");
  }
}
