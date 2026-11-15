package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.CertificateCrypto;
import com.certmanage.certificate.CertificateService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证自有证书所有层级按 DER 指纹去重，重复节点不覆盖原有资产
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
class CertificateImportDeduplicationTest {
  @Autowired CertificateCrypto crypto;
  @Autowired CertificateService service;
  @Autowired JdbcTemplate db;

  /**
   * @description 验证根、中间、叶子重复导入均跳过，共享链只新增指纹不同的同名叶子
   * @author Freddy
   * @date 2026-11-13
   */
  @Test
  void skipsEveryExistingLevelAndImportsDifferentFingerprint() throws Exception {
    var root = crypto.issue("ROOT", "Dedup root", 3650, null, null, List.of(), "GENERAL");
    var mid =
        crypto.issue(
            "INTERMEDIATE", "Dedup mid", 1000, root.cert(), root.key(), List.of(), "GENERAL");
    var leaf = crypto.issue("LEAF", "dedup.test", 90, mid.cert(), mid.key(), List.of(), "GENERAL");
    var parsed =
        new CertificateCrypto.Parsed(List.of(root.cert(), mid.cert(), leaf.cert()), leaf.key());
    var first = service.saveImport(parsed, "OWN", null, "GENERAL", "admin");
    assertEquals(3, first.get("importedCount"));
    assertEquals(0, first.get("skippedCount"));
    long id = ((Number) first.get("id")).longValue();
    service.setEnabled(id, false, "admin");
    var repeated = service.saveImport(parsed, "OWN", null, "HTTPS", "admin");
    assertEquals(id, ((Number) repeated.get("id")).longValue());
    assertEquals(0, repeated.get("importedCount"));
    assertEquals(3, repeated.get("skippedCount"));
    assertEquals(
        "GENERAL",
        db.queryForObject("select purpose from certificate_assets where id=?", String.class, id));
    assertFalse(
        db.queryForObject("select enabled from certificate_assets where id=?", Boolean.class, id));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from private_keys where own_asset_id=?", Integer.class, id));
    assertEquals(3, service.chainCertificates(id).size());

    var another =
        crypto.issue("LEAF", "dedup.test", 90, mid.cert(), mid.key(), List.of(), "GENERAL");
    assertNotEquals(
        crypto.fingerprint(leaf.cert().getEncoded()),
        crypto.fingerprint(another.cert().getEncoded()));
    var added =
        service.saveImport(
            new CertificateCrypto.Parsed(
                List.of(root.cert(), mid.cert(), another.cert()), another.key()),
            "OWN",
            null,
            "GENERAL",
            "admin");
    assertEquals(1, added.get("importedCount"));
    assertEquals(2, added.get("skippedCount"));
    assertNotEquals(id, ((Number) added.get("id")).longValue());
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from certificate_materials where common_name='dedup.test'",
            Integer.class));
  }
}
