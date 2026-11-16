package com.certmanage.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.ConfigSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * @description 验证旧配置补全后列表固定查询且不再解密凭据。
 * @author Freddy
 * @date 2026-11-16
 */
class ServerSummaryTest {
  /**
   * @description 验证旧版本只补全一次，后续列表单次查询且不解密秘密。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 隔离数据库或线程协作失败时传播
   */
  @Test
  void legacySummaryIsBackfilledOnceAndListingsUseOneConnection() throws Exception {
    var source =
        spy(
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    var db = new JdbcTemplate(source);
    db.execute(
        "create table managed_servers(id bigint,alias varchar,type varchar,description varchar,enabled boolean,config_revision bigint,current_config_id bigint,deleted_at timestamp)");
    db.execute(
        "create table managed_server_configs(id bigint,public_config_json varchar,secret_bundle_id bigint)");
    db.execute(
        "create table managed_server_trust_certificates(server_config_id bigint,material_id bigint)");
    var secrets = mock(ConfigSecrets.class);
    when(secrets.read(anyLong())).thenReturn(new LinkedHashMap<>(Map.of("password", "hidden")));
    when(secrets.readBatch(anyCollection()))
        .thenAnswer(
            call -> {
              var result = new HashMap<Long, Map<String, Object>>();
              for (Long id : call.<Collection<Long>>getArgument(0))
                result.put(id, Map.of("password", "hidden"));
              return result;
            });
    for (long id = 1; id <= 30; id++) {
      db.update(
          "insert into managed_servers values(?,?,'HTTP','',true,1,?,null)", id, "server" + id, id);
      db.update("insert into managed_server_configs values(?,'{\"type\":\"HTTP\"}',?)", id, id);
    }
    var service = new ServerService(db, secrets, new ObjectMapper());
    assertThat(service.list()).hasSize(30);
    clearInvocations(secrets, source);
    var list = service.list();
    assertThat(((Map<?, ?>) list.getFirst().get("config")).get("passwordConfigured"))
        .isEqualTo(true);
    assertThat(list.toString()).doesNotContain("hidden", "_credentialFlags");
    verifyNoInteractions(secrets);
    verify(source, times(1)).getConnection();
  }
}
