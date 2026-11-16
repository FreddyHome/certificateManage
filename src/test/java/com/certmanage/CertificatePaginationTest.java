package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.certmanage.certificate.CertificateCrypto;
import com.certmanage.certificate.CertificateService;
import com.certmanage.common.Audit;
import com.certmanage.common.SecretStore;
import com.certmanage.customer.CustomerService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 验证单连接池查询不嵌套取连接，以及证书分页、筛选和详情隔离契约
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:certificatepages;DB_CLOSE_DELAY=-1",
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.connection-timeout=1500",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@AutoConfigureMockMvc
class CertificatePaginationTest {
  @Autowired JdbcTemplate db;
  @Autowired CertificateService certificates;
  @Autowired MockMvc mvc;
  @Autowired HikariDataSource dataSource;
  @Autowired ApplicationContext context;
  private OffsetDateTime now;

  /**
   * @description 在独立数据库构造包含同名、通配符、未来、过期和归档的元数据
   * @author Freddy
   * @date 2026-11-16
   */
  @BeforeEach
  void seed() {
    db.update("delete from asset_chain_nodes");
    db.update("delete from certificate_assets");
    db.update("delete from certificate_materials");
    db.update("merge into customers(id,customer_name,name_key) key(id) values(1,'Acme','acme')");
    now = OffsetDateTime.now(ZoneOffset.UTC);
    seedAsset(101, "Root", "OWN", "ROOT", "GENERAL", -1, 120);
    seedAsset(102, "Duplicate", "OWN", "LEAF", "GENERAL", -1, 45);
    seedAsset(103, "Duplicate", "OWN", "LEAF", "GENERAL", -1, 45);
    seedAsset(104, "Acme_100%", "CUSTOMER", "LEAF", "AS2", -1, 7);
    seedAsset(105, "Future", "CUSTOMER", "LEAF", "HTTPS", 2, 40);
    seedAsset(106, "Expired", "CUSTOMER", "LEAF", "GENERAL", -40, -1);
    seedAsset(107, "Valid", "CUSTOMER", "LEAF", "GENERAL", -1, 45);
    seedAsset(108, "Archived", "OWN", "LEAF", "GENERAL", -1, 50);
    seedAsset(109, "Deleted", "OWN", "LEAF", "GENERAL", -1, 50);
    db.update("update certificate_assets set archived_at=current_timestamp where id=108");
    db.update("update certificate_assets set deleted_at=current_timestamp where id=109");
    db.update("update certificate_assets set enabled=false where id=107");
  }

  /**
   * @description 创建不参与密码学运算的合成公钥元数据和单节点链
   * @author Freddy
   * @date 2026-11-16
   * @param id 材料与资产编号
   * @param cn 证书通用名称
   * @param owner 资产归属
   * @param kind 资产层级
   * @param purpose 业务用途
   * @param start 生效时间相对天数
   * @param end 到期时间相对天数
   */
  private void seedAsset(
      long id, String cn, String owner, String kind, String purpose, int start, int end) {
    String sha = String.format("%064x", id);
    db.update(
        "insert into certificate_materials(id,fingerprint_sha256,certificate_der,common_name,subject_dn,issuer_dn,serial_hex,not_before,not_after,public_key_algorithm,signature_algorithm,spki_sha256,is_ca) values(?,?,X'3000',?,?,?,?,?,?,'RSA','SHA256withRSA',?,?)",
        id,
        sha,
        cn,
        "CN=" + cn,
        "CN=issuer",
        Long.toString(id),
        now.plusDays(start),
        now.plusDays(end),
        sha,
        kind.equals("ROOT"));
    db.update(
        "insert into certificate_assets(id,material_id,owner_type,customer_id,scope_key,purpose,certificate_kind,chain_depth,issuer_material_id,source_type,created_by) values(?,?,?,?,?,?,?,1,?,'IMPORTED',1)",
        id,
        id,
        owner,
        owner.equals("OWN") ? null : 1L,
        owner.equals("OWN") ? "OWN" : "CUSTOMER:1",
        purpose,
        kind,
        id);
    db.update("insert into asset_chain_nodes values(?,1,?)", id, id);
  }

  /**
   * @description 普通、归档及单条详情查询在单连接池下均不得等待第二个连接
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void legacyQueriesWorkWithOneConnection() {
    try (var pool = new HikariDataSource()) {
      pool.setJdbcUrl(dataSource.getJdbcUrl());
      pool.setUsername(dataSource.getUsername());
      pool.setPassword(dataSource.getPassword());
      pool.setMaximumPoolSize(1);
      pool.setMinimumIdle(1);
      pool.setConnectionTimeout(250);
      var singleConnection =
          new CertificateService(
              new JdbcTemplate(pool),
              context.getBean(TransactionTemplate.class),
              context.getBean(CertificateCrypto.class),
              context.getBean(SecretStore.class),
              context.getBean(Audit.class),
              context.getBean(CustomerService.class));
      assertEquals(3, assertDoesNotThrow(() -> singleConnection.list("OWN")).size());
      assertEquals(1, assertDoesNotThrow(() -> singleConnection.archived(null)).size());
      assertEquals(102L, assertDoesNotThrow(() -> singleConnection.detail(102)).get("id"));
    }
  }

  /**
   * @description 同名同到期资产按ID稳定分页，列表不返回详情大字段
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void pagesByKindAndOmitsDetailFields() throws Exception {
    mvc.perform(
            get("/api/v1/certificates/page")
                .param("owner", "OWN")
                .param("kind", "LEAF")
                .param("page", "2")
                .param("size", "1")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(103))
        .andExpect(jsonPath("$.items[0].subject").doesNotExist())
        .andExpect(jsonPath("$.items[0].san").doesNotExist())
        .andExpect(jsonPath("$.items[0].fingerprint").doesNotExist());
    mvc.perform(
            get("/api/v1/certificates/page")
                .param("kind", "ROOT")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(101));
  }

  /**
   * @description 搜索通配符按字面处理，客户与用途筛选组合生效
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void filtersLiteralSearchCustomerAndPurpose() throws Exception {
    for (String query : new String[] {"_", "%", "ACME"}) {
      mvc.perform(
              get("/api/v1/certificates/page")
                  .param("owner", "CUSTOMER")
                  .param("query", query)
                  .param("purpose", "AS2")
                  .with(user("admin").roles("ADMIN")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1))
          .andExpect(jsonPath("$.items[0].id").value(104));
    }
    mvc.perform(
            get("/api/v1/certificates/page")
                .param("owner", "CUSTOMER")
                .param("query", "Acme")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(4));
  }

  /**
   * @description 四种有效期状态按同一规则筛选，停用证书仍可管理
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void filtersAllValidityStates() throws Exception {
    String[] states = {"即将过期", "未生效", "已过期", "有效"};
    int[] ids = {104, 105, 106, 107};
    for (int i = 0; i < states.length; i++) {
      mvc.perform(
              get("/api/v1/certificates/page")
                  .param("owner", "CUSTOMER")
                  .param("status", states[i])
                  .with(user("admin").roles("ADMIN")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1))
          .andExpect(jsonPath("$.items[0].id").value(ids[i]))
          .andExpect(jsonPath("$.items[0].status").value(states[i]));
    }
  }

  /**
   * @description 归档分页不混入普通或已删除资产，越界页面返回空条目
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void separatesArchivedAndSupportsEmptyPages() throws Exception {
    mvc.perform(get("/api/v1/certificates/archived/page").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(108));
    mvc.perform(
            get("/api/v1/certificates/page")
                .param("page", "999")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  /**
   * @description 无效筛选与分页参数遵守项目422错误契约，不允许无界页大小
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void rejectsInvalidFiltersAndPageBounds() throws Exception {
    String[][] parameters = {
      {"owner", "OTHER"},
      {"kind", "OTHER"},
      {"purpose", "OTHER"},
      {"status", "OTHER"},
      {"page", "0"},
      {"page", "1000001"},
      {"size", "0"},
      {"size", "101"},
      {"query", "a".repeat(201)}
    };
    for (var parameter : parameters) {
      mvc.perform(
              get("/api/v1/certificates/page")
                  .param(parameter[0], parameter[1])
                  .with(user("admin").roles("ADMIN")))
          .andExpect(status().isUnprocessableEntity());
    }
  }
}
