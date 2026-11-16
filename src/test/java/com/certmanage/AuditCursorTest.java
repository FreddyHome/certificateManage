package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @description 验证审计游标稳定排序、条件绑定、可选计数和旧分页兼容
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:auditcursor;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@AutoConfigureMockMvc
class AuditCursorTest {
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  private final List<Long> expected = new ArrayList<>();

  /**
   * @description 构造同一时刻的多条事件，确保游标必须同时使用时间和ID
   * @author Freddy
   * @date 2026-11-16
   */
  @BeforeEach
  void seed() {
    db.update("delete from execution_logs where source='CURSOR_TEST'");
    expected.clear();
    for (int i = 0; i < 7; i++) {
      db.update(
          "insert into execution_logs(occurred_at,source,event_code,outcome,actor_name,message,detail_json) values(?,'CURSOR_TEST','CURSOR','SUCCESS','admin',?,'{}')",
          OffsetDateTime.parse("2026-11-15T00:00:00Z"),
          "event-" + i);
      expected.add(
          db.queryForObject(
              "select max(id) from execution_logs where source='CURSOR_TEST'", Long.class));
    }
    Collections.reverse(expected);
  }

  /**
   * @description 相同时间事件不重复不遗漏，新写入较新事件不打乱后续页
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP或JSON断言失败
   */
  @Test
  void cursorPagesAreStableAcrossConcurrentInsert() throws Exception {
    var seen = new ArrayList<Long>();
    String cursor = "";
    for (int page = 0; page < 4; page++) {
      var response = page(cursor, page == 0);
      assertTrue(response.has("hasMore"));
      for (var row : response.path("items")) seen.add(row.path("id").asLong());
      if (page == 0) {
        assertEquals(7, response.path("total").asLong());
        db.update(
            "insert into execution_logs(source,event_code,outcome,actor_name,message,detail_json) values('CURSOR_TEST','NEW','SUCCESS','admin','newer','{}')");
      } else assertFalse(response.has("total"));
      cursor = response.path("nextCursor").isNull() ? "" : response.path("nextCursor").asText();
      if (page < 3) assertFalse(cursor.isEmpty());
      else {
        assertFalse(response.path("hasMore").asBoolean());
        assertTrue(cursor.isEmpty());
      }
    }
    assertEquals(expected, seen);
  }

  /**
   * @description 游标与筛选条件绑定，畸形或跨条件游标不能静默返回错误页
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP断言失败
   */
  @Test
  void rejectsMalformedAndCrossFilterCursors() throws Exception {
    String cursor = page("", false).path("nextCursor").asText();
    for (String value : List.of("not-a-cursor", "a".repeat(300))) {
      mvc.perform(
              get("/api/v1/execution-logs")
                  .param("paging", "cursor")
                  .param("cursor", value)
                  .with(user("admin").roles("ADMIN")))
          .andExpect(status().isUnprocessableEntity());
    }
    mvc.perform(
            get("/api/v1/execution-logs")
                .param("paging", "cursor")
                .param("cursor", cursor)
                .param("source", "OTHER")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isUnprocessableEntity());
  }

  /**
   * @description 原分页调用仍保留总数、页码和详情字段
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP或JSON断言失败
   */
  @Test
  void legacyOffsetRemainsCompatible() throws Exception {
    var r =
        mvc.perform(
                get("/api/v1/execution-logs")
                    .param("source", "CURSOR_TEST")
                    .param("page", "2")
                    .param("size", "2")
                    .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn();
    var page = json.readTree(r.getResponse().getContentAsString());
    assertEquals(7, page.path("total").asLong());
    assertEquals(2, page.path("page").asInt());
    assertEquals(expected.get(2).longValue(), page.path("items").get(0).path("id").asLong());
  }

  /**
   * @description 按固定条件读取游标页
   * @author Freddy
   * @date 2026-11-16
   * @param cursor 前页返回的游标，首页为空
   * @param count 是否执行总数查询
   * @return 当前页JSON
   * @throws Exception HTTP或JSON读取失败
   */
  private JsonNode page(String cursor, boolean count) throws Exception {
    var r =
        mvc.perform(
                get("/api/v1/execution-logs")
                    .param("source", "CURSOR_TEST")
                    .param("paging", "cursor")
                    .param("cursor", cursor)
                    .param("includeTotal", Boolean.toString(count))
                    .param("size", "2")
                    .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(r.getResponse().getContentAsString());
  }
}
