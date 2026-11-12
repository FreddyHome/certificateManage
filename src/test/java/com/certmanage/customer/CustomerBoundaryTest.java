package com.certmanage.customer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证客户 HTTP 契约、归一化复用和事务回滚边界
 * @author Freddy
 * @date 2026-11-12
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key"
    })
@AutoConfigureMockMvc
class CustomerBoundaryTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired CustomerService customers;

  /**
   * @description 验证大小写及全角等价名称在导入时复用同一客户
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  @Transactional
  void importsReuseCanonicalCustomer() {
    long id = customers.findOrCreateForImport("  Ａｃｍｅ  ");
    assertEquals(id, customers.findOrCreateForImport("acme"));
    assertEquals("acme", CustomerNames.key("  Ａｃｍｅ  "));
    assertEquals(
        1,
        db.queryForObject("select count(*) from customers where name_key='acme'", Integer.class));
  }

  /**
   * @description 验证客户名显式为空及超长描述不会泄漏为服务端错误
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception MockMvc 请求执行失败时抛出
   */
  @Test
  void rejectsNullNameAndOversizedDescription() throws Exception {
    for (String body :
        new String[] {
          "{\"name\":null}", "{\"name\":\"valid\",\"description\":\"" + "x".repeat(2001) + "\"}"
        }) {
      mvc.perform(
              post("/api/v1/customers")
                  .with(user("admin").roles("ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  /**
   * @description 审计失败时客户创建必须和业务事务一起回滚
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void rollsBackCustomerIfAuditFails() {
    String name = "rollback-" + UUID.randomUUID();
    assertThrows(
        RuntimeException.class,
        () -> customers.add(new CustomerRequest(name, "", "", ""), "missing-actor"));
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from customers where customer_name=?", Integer.class, name));
  }
}
