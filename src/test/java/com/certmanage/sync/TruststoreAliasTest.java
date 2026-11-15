package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.CertificateCrypto;
import com.certmanage.certificate.CertificateService;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证信任证书自动别名、完整链独立条目及历史别名兼容
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
class TruststoreAliasTest {
  @Autowired CertificateCrypto crypto;
  @Autowired CertificateService certs;
  @Autowired WorkflowValidator validator;
  @Autowired StoreMaker maker;

  /**
   * @description 验证三层独立自动别名、同名叶子区分、材料去重及两种格式输出
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 证书生成或证书库读取失败时抛出
   */
  @Test
  void generatesStableAliasesForEachCertificateAndBothFormats() throws Exception {
    var root = crypto.issue("ROOT", "Alias Root", 3650, null, null, List.of(), "GENERAL");
    var mid =
        crypto.issue(
            "INTERMEDIATE", "Alias Mid", 1000, root.cert(), root.key(), List.of(), "GENERAL");
    var leaf =
        crypto.issue("LEAF", "*.yisquare.com.cn", 90, mid.cert(), mid.key(), List.of(), "GENERAL");
    var other =
        crypto.issue("LEAF", "*.yisquare.com.cn", 90, mid.cert(), mid.key(), List.of(), "GENERAL");
    long first =
        ((Number)
                certs
                    .saveImport(
                        new CertificateCrypto.Parsed(
                            List.of(root.cert(), mid.cert(), leaf.cert()), leaf.key()),
                        "OWN",
                        null,
                        "GENERAL",
                        "admin")
                    .get("id"))
            .longValue();
    long second =
        ((Number)
                certs
                    .saveImport(
                        new CertificateCrypto.Parsed(
                            List.of(root.cert(), mid.cert(), other.cert()), other.key()),
                        "OWN",
                        null,
                        "GENERAL",
                        "admin")
                    .get("id"))
            .longValue();
    var expected = new HashSet<String>();
    var chain = List.of(root.cert(), mid.cert(), leaf.cert(), other.cert());
    var prefixes = List.of("alias-root", "alias-mid", "yisquare.com.cn", "yisquare.com.cn");
    for (int i = 0; i < chain.size(); i++)
      expected.add(prefixes.get(i) + "-" + crypto.fingerprint(chain.get(i).getEncoded()));
    for (String format : List.of("JKS", "PKCS12")) {
      var config =
          prepare(
              format,
              List.of(
                  Map.of("assetId", first, "includeChain", true),
                  Map.of("assetId", second, "alias", "", "includeChain", true)));
      byte[] bytes = maker.make("TRUSTSTORE", config, Map.of("password", "storepass123"));
      var store = KeyStore.getInstance(format);
      store.load(new ByteArrayInputStream(bytes), "storepass123".toCharArray());
      assertEquals(expected, new HashSet<>(Collections.list(store.aliases())));
      for (String alias : expected) assertTrue(store.isCertificateEntry(alias));
      var repeated = prepare(format, List.of(Map.of("assetId", first, "includeChain", true)));
      var entries = (List<?>) repeated.get("resolvedEntries");
      assertEquals(3, entries.size());
      assertTrue(entries.stream().allMatch(e -> expected.contains(((Map<?, ?>) e).get("alias"))));
    }
    var legacy =
        prepare("JKS", List.of(Map.of("assetId", first, "alias", "legacy", "includeChain", true)));
    var legacyEntries = (List<?>) legacy.get("resolvedEntries");
    assertEquals(
        Set.of("legacy", "legacy-ca-1", "legacy-ca-2"),
        legacyEntries.stream()
            .map(e -> ((Map<?, ?>) e).get("alias"))
            .collect(java.util.stream.Collectors.toSet()));
  }

  /**
   * @description 按实际工作流校验入口解析信任证书配置
   * @author Freddy
   * @date 2026-11-13
   * @param format 证书库格式
   * @param entries 信任证书输入条目
   * @return 冻结材料和别名后的配置
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> prepare(String format, List<Map<String, Object>> entries) {
    var step =
        new LinkedHashMap<String, Object>(
            Map.of(
                "key",
                "trust",
                "type",
                "TRUSTSTORE",
                "config",
                Map.of("format", format, "entries", entries)));
    validator.prepare(List.of(step), false);
    return (Map<String, Object>) step.get("config");
  }
}
