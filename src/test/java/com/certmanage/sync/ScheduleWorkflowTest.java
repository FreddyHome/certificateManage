package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 验证业务计划保存、历史兼容以及独立预览的只读和权限边界
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
@AutoConfigureMockMvc
@Transactional
class ScheduleWorkflowTest {
  @Autowired WorkflowService workflows;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired com.certmanage.server.ServerService servers;

  /**
   * @description 就绪日历计划自动启用，草稿保留参数；旧API仍支持显式关闭
   * @author Freddy
   * @date 2026-11-16
   * @param frequency 新日历计划或兼容的旧频率
   */
  @ParameterizedTest
  @ValueSource(strings = {"DAILY", "CALENDAR"})
  void enabledPlanUsesCanonicalFieldsAndCanBeDisabled(String frequency) {
    long server =
        ((Number)
                servers
                    .save(
                        null,
                        Map.of(
                            "alias",
                            "schedule-" + UUID.randomUUID(),
                            "type",
                            "HTTP",
                            "config",
                            Map.of("baseUrl", "http://127.0.0.1:1")))
                    .get("id"))
            .longValue();
    var input =
        new LinkedHashMap<String, Object>(
            Map.of(
                "name",
                "启用计划",
                "state",
                "READY",
                "scheduleEnabled",
                !frequency.equals("CALENDAR"),
                "schedule",
                frequency.equals("CALENDAR")
                    ? Map.of("frequency", "CALENDAR", "hours", List.of(2), "minutes", List.of(0))
                    : Map.of("frequency", "DAILY", "time", "02:00"),
                "steps",
                List.of(
                    Map.of(
                        "key",
                        "http",
                        "type",
                        "HTTP",
                        "name",
                        "请求",
                        "config",
                        Map.of(
                            "serverId",
                            server,
                            "method",
                            "GET",
                            "bodyMode",
                            "NONE",
                            "path",
                            "/test")))));
    var saved = workflows.save(null, input, "admin");
    long id = ((Number) saved.get("id")).longValue();
    assertEquals("每天 02:00 执行 · 北京时间", saved.get("scheduleSummary"));
    assertNotNull(saved.get("nextFireAt"));
    assertEquals(
        "0 0 2 * * *",
        db.queryForObject("select cron from sync_tasks where id=?", String.class, id));
    saved.put("scheduleEnabled", false);
    if (frequency.equals("CALENDAR")) saved.put("state", "DRAFT");
    var disabled = workflows.save(id, saved, "admin");
    assertNull(disabled.get("nextFireAt"));
    assertEquals(saved.get("schedule"), disabled.get("schedule"));
  }

  /**
   * @description 结构化参数决定调度字段，关闭定时保留参数且草稿不能开启
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void savesCanonicalPlanAndPreservesDisabledParameters() {
    var input =
        new LinkedHashMap<String, Object>(
            Map.of(
                "name",
                "计划测试",
                "state",
                "DRAFT",
                "steps",
                List.of(),
                "cron",
                "garbage",
                "timezone",
                "bad/zone",
                "schedule",
                Map.of("frequency", "WEEKLY", "time", "02:00", "weekdays", List.of(1, 5))));
    var saved = workflows.save(null, input, "admin");
    assertEquals("0 0 2 * * 1,5", saved.get("cron"));
    assertEquals("Asia/Shanghai", saved.get("timezone"));
    assertEquals("每周一、周五 02:00 执行 · 北京时间（未启用）", saved.get("scheduleSummary"));
    assertEquals("WEEKLY", SyncData.map(saved.get("schedule")).get("frequency"));
    long id = ((Number) saved.get("id")).longValue();
    assertNotNull(
        db.queryForObject("select schedule_json from sync_tasks where id=?", String.class, id));
    saved.put("name", "更新名称");
    var updated = workflows.save(id, saved, "admin");
    assertEquals(saved.get("schedule"), updated.get("schedule"));
    updated.put("scheduleEnabled", true);
    assertThrows(IllegalArgumentException.class, () -> workflows.save(id, updated, "admin"));
  }

  /**
   * @description 旧客户端传入的复杂表达式保持原文，编辑其他字段不改写计划
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void preservesLegacyCustomRuleAcrossEdits() {
    var input =
        new LinkedHashMap<String, Object>(
            Map.of(
                "name",
                "历史测试",
                "state",
                "DRAFT",
                "steps",
                List.of(),
                "cron",
                "15 0 2 * * MON-FRI",
                "timezone",
                "Asia/Shanghai"));
    var saved = workflows.save(null, input, "admin");
    long id = ((Number) saved.get("id")).longValue();
    db.update("update sync_tasks set schedule_json=null where id=?", id);
    var legacy = workflows.detail(id);
    assertEquals("CUSTOM", SyncData.map(legacy.get("schedule")).get("frequency"));
    legacy.put("description", "仅修改说明");
    var updated = workflows.save(id, legacy, "admin");
    assertEquals("15 0 2 * * MON-FRI", updated.get("cron"));
    assertNull(updated.get("nextFireAt"));
    assertEquals(
        "DAILY",
        SyncData.map(
                workflows
                    .save(
                        null, Map.of("name", "旧默认", "state", "DRAFT", "steps", List.of()), "admin")
                    .get("schedule"))
            .get("frequency"));
  }

  /**
   * @description 预览不要求完整任务且不保存任务、步骤或执行记录，受认证和CSRF保护
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP测试失败
   */
  @Test
  void previewIsIndependentAndProtected() throws Exception {
    int tasks = db.queryForObject("select count(*) from sync_tasks", Integer.class);
    int runs = db.queryForObject("select count(*) from sync_runs", Integer.class);
    String body = json.writeValueAsString(Map.of("frequency", "DAILY", "time", "02:00"));
    mvc.perform(
            post("/api/v1/sync-tasks/schedule-preview")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").value("每天 02:00 执行 · 北京时间"))
        .andExpect(jsonPath("$.nextTimes.length()").value(3));
    assertEquals(tasks, db.queryForObject("select count(*) from sync_tasks", Integer.class));
    assertEquals(runs, db.queryForObject("select count(*) from sync_runs", Integer.class));
    mvc.perform(
            post("/api/v1/sync-tasks/schedule-preview")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/sync-tasks/schedule-preview")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/sync-tasks/schedule-preview")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"frequency\":\"WEEKLY\",\"time\":\"02:00\",\"weekdays\":[]}"))
        .andExpect(status().isUnprocessableEntity());
  }
}
