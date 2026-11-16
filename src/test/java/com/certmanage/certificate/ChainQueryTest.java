package com.certmanage.certificate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * @description 验证整链公钥单次查询与请求范围材料复用。
 * @author Freddy
 * @date 2026-11-16
 */
class ChainQueryTest {
  /**
   * @description 验证整链单次查询、公钥范围复用及结束后重新读取。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 隔离数据库或线程协作失败时传播
   */
  @Test
  void readsWholeChainUsingOneConnection() throws Exception {
    var source =
        spy(
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    var db = new JdbcTemplate(source);
    db.execute("create table certificate_materials(id bigint,certificate_der blob)");
    db.execute(
        "create table asset_chain_nodes(asset_id bigint,material_id bigint,node_position int)");
    var crypto = new CertificateCrypto();
    var root = crypto.issue("ROOT", "query-root", 365, null, null, List.of(), "GENERAL");
    var leaf =
        crypto.issue("LEAF", "query-leaf", 30, root.cert(), root.key(), List.of(), "GENERAL");
    db.update("insert into certificate_materials values(?,?)", 1, root.cert().getEncoded());
    db.update("insert into certificate_materials values(?,?)", 2, leaf.cert().getEncoded());
    db.update("insert into asset_chain_nodes values(1,1,1),(1,2,2)");
    var service = new CertificateService(db, null, crypto, null, null, null);
    clearInvocations(source);
    assertThat(service.chainCertificates(1)).containsExactly(root.cert(), leaf.cert());
    verify(source, times(1)).getConnection();
    clearInvocations(source);
    try (var scope = service.materialScope()) {
      var first = service.chainCertificates(1);
      assertThat(service.chainCertificates(1)).isSameAs(first);
      assertThat(service.material(1)).isSameAs(first.getFirst());
    }
    verify(source, times(1)).getConnection();
    service.chainCertificates(1);
    verify(source, times(2)).getConnection();
  }
}
