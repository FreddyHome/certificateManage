package com.certmanage.sync;

import static com.certmanage.common.Errors.require;

import com.certmanage.certificate.CertificateService;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * @description 在内存中新建证书库，所有输入资产必须启用且私钥条目限定自有证书
 * @author Freddy
 * @date 2026-11-12
 */
@Component
public class StoreMaker {
  private final CertificateService certificates;

  /**
   * @description 注入证书业务服务
   * @author Freddy
   * @date 2026-11-12
   * @param certificates 证书读取及业务使用状态校验服务
   */
  public StoreMaker(CertificateService certificates) {
    this.certificates = certificates;
  }

  /**
   * @description 仅使用启用资产生成新的JKS或PKCS12，结束后清理密码内存
   * @author Freddy
   * @date 2026-11-12
   * @param type KEYSTORE或TRUSTSTORE
   * @param config 包含格式及已解析条目的配置
   * @param secrets 证书库及可选私钥密码
   * @return 新证书库文件的完整字节
   * @throws Exception 证书禁用、输入不合法或编码失败时抛出
   */
  public byte[] make(String type, Map<String, Object> config, Map<String, Object> secrets)
      throws Exception {
    String format = Objects.toString(config.get("format"), "PKCS12");
    require(Set.of("JKS", "PKCS12").contains(format), "Store格式错误");
    char[] password = Objects.toString(secrets.get("password"), "").toCharArray();
    require(password.length >= 8, "Store密码至少8位");
    char[] keyPassword =
        Objects.toString(secrets.get("keyPassword"), new String(password)).toCharArray();
    if (format.equals("PKCS12")) keyPassword = password;
    try {
      var store = KeyStore.getInstance(format);
      store.load(null, password);
      var aliases = new HashSet<String>();
      int privateCount = 0;
      for (var entry : SyncData.maps(config.get("resolvedEntries"))) {
        String alias = SyncData.str(entry, "alias", "");
        require(!alias.isBlank() && aliases.add(alias.toLowerCase(Locale.ROOT)), "Store别名重复或为空");
        long asset = SyncData.id(entry, "assetId"), material = SyncData.id(entry, "materialId");
        certificates.requireEnabled(asset);
        if ("PRIVATE".equals(entry.get("role"))) {
          require(type.equals("KEYSTORE"), "Truststore禁止私钥");
          var detail = certificates.detail(asset);
          require(
              "OWN".equals(detail.get("owner")) && Boolean.TRUE.equals(detail.get("hasKey")),
              "私钥条目必须选择含私钥的自有证书");
          var chain = certificates.chainCertificates(asset).reversed();
          require(!chain.isEmpty(), "证书链为空");
          store.setKeyEntry(
              alias, certificates.key(asset), keyPassword, chain.toArray(X509Certificate[]::new));
          privateCount++;
        } else store.setCertificateEntry(alias, certificates.material(material));
      }
      require(store.size() > 0, "Store至少包含一个条目");
      require(!type.equals("KEYSTORE") || privateCount > 0, "Keystore至少包含一个私钥条目");
      var out = new ByteArrayOutputStream();
      store.store(out, password);
      return out.toByteArray();
    } finally {
      Arrays.fill(password, '\0');
      Arrays.fill(keyPassword, '\0');
    }
  }
}
