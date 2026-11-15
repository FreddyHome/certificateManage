package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.certificate.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 验证两类证书归档、恢复、导出和删除生命周期
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class CertificateArchiveTest {
  @Autowired CertificateService service;
  @Autowired CertificateController controller;
  @Autowired TransactionTemplate tx;

  /**
   * @description 验证归档保留启用状态、阻止业务、允许查看导出且需归档后删除
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void preservesBothOwnersAndEnabledStates() {
    tx.executeWithoutResult(
        status -> {
          long root =
              ((Number)
                      service
                          .issue(
                              Map.of("kind", "ROOT", "cn", "Archive root", "days", 3650), "admin")
                          .get("id"))
                  .longValue();
          long intermediate =
              ((Number)
                      service
                          .issue(
                              Map.of(
                                  "kind",
                                  "INTERMEDIATE",
                                  "cn",
                                  "Archive intermediate",
                                  "days",
                                  1825,
                                  "issuerId",
                                  root),
                              "admin")
                          .get("id"))
                  .longValue();
          long own =
              ((Number)
                      service
                          .issue(
                              Map.of(
                                  "kind",
                                  "LEAF",
                                  "cn",
                                  "Archive leaf",
                                  "days",
                                  365,
                                  "issuerId",
                                  intermediate),
                              "admin")
                          .get("id"))
                  .longValue();
          long customer =
              ((Number)
                      service
                          .saveImport(
                              new CertificateCrypto.Parsed(service.chainCertificates(own), null),
                              "CUSTOMER",
                              "Archive customer",
                              "GENERAL",
                              "admin")
                          .get("id"))
                  .longValue();
          service.setEnabled(customer, false, "admin");
          for (long id : List.of(own, customer)) {
            assertThrows(IllegalArgumentException.class, () -> service.deleteArchived(id, "admin"));
            service.archive(id, "admin");
            assertNotNull(service.detail(id).get("archivedAt"));
            assertThrows(IllegalArgumentException.class, () -> service.requireEnabled(id));
            assertThrows(
                IllegalArgumentException.class, () -> service.setEnabled(id, true, "admin"));
            assertFalse(
                service.list((String) service.detail(id).get("owner")).stream()
                    .anyMatch(row -> row.get("id").equals(id)));
            assertTrue(service.archived(null).stream().anyMatch(row -> row.get("id").equals(id)));
            try {
              assertTrue(
                  controller.export(id, Map.of("format", "PEM"), () -> "admin").getBody().length
                      > 0);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
            service.restore(id, "admin");
            assertNull(service.detail(id).get("archivedAt"));
          }
          assertEquals(true, service.detail(own).get("enabled"));
          assertEquals(false, service.detail(customer).get("enabled"));
          service.requireEnabled(own);
          assertThrows(IllegalArgumentException.class, () -> service.requireEnabled(customer));
          service.archive(customer, "admin");
          service.saveImport(
              new CertificateCrypto.Parsed(service.chainCertificates(own), null),
              "CUSTOMER",
              "Archive customer",
              "GENERAL",
              "admin");
          assertNotNull(service.detail(customer).get("archivedAt"));
          service.deleteArchived(customer, "admin");
          assertThrows(IllegalArgumentException.class, () -> service.detail(customer));
          assertThrows(IllegalArgumentException.class, () -> service.restore(customer, "admin"));
          service.archive(own, "admin");
          service.deleteArchived(own, "admin");
          assertThrows(IllegalArgumentException.class, () -> service.detail(own));
          status.setRollbackOnly();
        });
  }
}
