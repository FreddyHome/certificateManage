package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 验证归档删除时的证书链引用保护
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key"
    })
class CertificateDeleteTest {
  @Autowired CertificateService service;
  @Autowired TransactionTemplate tx;

  /**
   * @description 创建归档删除验证所需的证书链节点
   * @author Freddy
   * @date 2026-11-12
   * @param kind 证书层级
   * @param cn 证书名称
   * @param parent 上级资产 ID
   * @return 新资产 ID
   */
  long issue(String kind, String cn, Long parent) {
    var b = new HashMap<String, Object>();
    b.put("kind", kind);
    b.put("cn", cn);
    b.put("days", kind.equals("ROOT") ? 3650 : kind.equals("INTERMEDIATE") ? 1825 : 365);
    if (parent != null) b.put("issuerId", parent);
    return ((Number) service.issue(b, "admin").get("id")).longValue();
  }

  /**
   * @description 验证归档父证书受引用保护且可从终端向根依次删除
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void protectsAncestorsAndAllowsDeletingFromLeafUp() {
    tx.execute(
        s -> {
          long root = issue("ROOT", "Delete Root", null),
              mid = issue("INTERMEDIATE", "Delete Mid", root),
              leaf = issue("LEAF", "Delete Leaf", mid);
          service.archive(root, "admin");
          service.archive(mid, "admin");
          service.archive(leaf, "admin");
          assertThrows(IllegalArgumentException.class, () -> service.deleteArchived(root, "admin"));
          assertThrows(IllegalArgumentException.class, () -> service.deleteArchived(mid, "admin"));
          service.deleteArchived(leaf, "admin");
          assertThrows(IllegalArgumentException.class, () -> service.detail(leaf));
          service.deleteArchived(mid, "admin");
          service.deleteArchived(root, "admin");
          assertFalse(service.list("OWN").stream().anyMatch(r -> r.get("id").equals(root)));
          s.setRollbackOnly();
          return null;
        });
  }

  /**
   * @description 验证客户证书链保护自有资产且归档客户资产可删除
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void customerChainAlsoProtectsOwnCertificates() {
    tx.execute(
        s -> {
          long root = issue("ROOT", "Customer Ref Root", null),
              mid = issue("INTERMEDIATE", "Customer Ref Mid", root),
              leaf = issue("LEAF", "Customer Ref Leaf", mid);
          long customer =
              ((Number)
                      service
                          .saveImport(
                              new CertificateCrypto.Parsed(service.chainCertificates(leaf), null),
                              "CUSTOMER",
                              "Delete Test Customer",
                              "GENERAL",
                              "admin")
                          .get("id"))
                  .longValue();
          service.archive(leaf, "admin");
          service.archive(root, "admin");
          assertThrows(IllegalArgumentException.class, () -> service.deleteArchived(leaf, "admin"));
          assertThrows(IllegalArgumentException.class, () -> service.deleteArchived(root, "admin"));
          assertThrows(
              IllegalArgumentException.class, () -> service.deleteArchived(customer, "admin"));
          service.archive(customer, "admin");
          service.deleteArchived(customer, "admin");
          service.deleteArchived(leaf, "admin");
          s.setRollbackOnly();
          return null;
        });
  }
}
