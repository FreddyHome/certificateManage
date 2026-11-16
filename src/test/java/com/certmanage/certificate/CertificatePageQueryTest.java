package com.certmanage.certificate;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * @description 使用固定时钟验证数据库筛选与展示在精确时间边界一致
 * @author Freddy
 * @date 2026-11-16
 */
class CertificatePageQueryTest {
  /**
   * @description 截止、生效和预警边界取值一致，零天预警不产生即将过期资产
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void exactValidityBoundariesMatchSqlFilters() {
    Instant now = Instant.parse("2026-11-16T00:00:00Z");
    try (var source =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:boundary" + UUID.randomUUID(), "sa", "", true)) {
      var db = new JdbcTemplate(source);
      db.execute("create table notification_policy(id int, warning_days int)");
      db.execute("create table customers(id bigint, customer_name varchar)");
      db.execute("create table private_keys(own_asset_id bigint)");
      db.execute(
          "create table certificate_materials(id bigint, common_name varchar, serial_hex varchar, not_before timestamp with time zone, not_after timestamp with time zone)");
      db.execute(
          "create table certificate_assets(id bigint, material_id bigint, owner_type varchar, enabled boolean, archived_at timestamp with time zone, deleted_at timestamp with time zone, customer_id bigint, purpose varchar, certificate_kind varchar, chain_depth int, issuer_material_id bigint)");
      db.update("insert into notification_policy values(1,30)");
      Instant[] starts = {now, now.minusSeconds(100), now.plusNanos(1000), now.minusSeconds(100)};
      Instant[] ends = {
        now.plusSeconds(30 * 86400L),
        now,
        now.plusSeconds(100),
        now.plusSeconds(30 * 86400L).minusNanos(1000)
      };
      String[] states = {"有效", "已过期", "未生效", "即将过期"};
      for (int i = 0; i < states.length; i++) {
        db.update(
            "insert into certificate_materials values(?,?,?,?,?)",
            i + 1,
            "boundary-" + i,
            Integer.toString(i + 1),
            starts[i].atOffset(ZoneOffset.UTC),
            ends[i].atOffset(ZoneOffset.UTC));
        db.update(
            "insert into certificate_assets values(?,?,'OWN',true,null,null,null,'GENERAL','LEAF',1,?)",
            i + 1,
            i + 1,
            i + 1);
      }
      var queries = new CertificatePageQuery(db, Clock.fixed(now, ZoneOffset.UTC));
      for (int i = 0; i < states.length; i++) {
        var page = queries.page("OWN", null, null, null, states[i], 1, 20, false);
        assertEquals(1L, page.get("total"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) page.get("items");
        assertEquals((long) i + 1, rows.getFirst().get("id"));
        assertEquals(states[i], rows.getFirst().get("status"));
      }
      db.update("update notification_policy set warning_days=0");
      assertEquals(0L, queries.page("OWN", null, null, null, "即将过期", 1, 20, false).get("total"));
    }
  }
}
