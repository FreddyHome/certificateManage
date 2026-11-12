package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.customer.CustomerController;
import com.certmanage.customer.CustomerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证客户标识字段的保存、编辑及清除契约
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key"
    })
class CustomerIdentificationTest {
  @Autowired CustomerController customers;

  /**
   * @description 可选标识能够保存，空值编辑后按原契约返回空字符串
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  @Transactional
  void identificationCanBeSavedEditedAndCleared() {
    customers.add(
        new CustomerRequest("Identification Customer", "", "partner-001", "AS2"), () -> "admin");
    var row =
        customers.list().stream()
            .filter(r -> r.name().equals("Identification Customer"))
            .findFirst()
            .orElseThrow();
    assertEquals("partner-001", row.externalId());
    assertEquals("AS2", row.idType());
    long id = row.id();
    customers.edit(id, new CustomerRequest("Identification Customer", "", "", ""), () -> "admin");
    row = customers.list().stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    assertEquals("", row.externalId());
    assertEquals("", row.idType());
  }
}
