package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * @description 验证V10数据库增加结构化计划字段时保留既有调度配置和启用状态
 * @author Freddy
 * @date 2026-11-16
 */
class ScheduleMigrationTest {
  /**
   * @description 在真实旧版本表中写入复杂计划，再升级验证原记录及空计划字段
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 数据库迁移或读取失败
   */
  @Test
  void upgradesExistingScheduleWithoutRewritingIt() throws Exception {
    String url = "jdbc:h2:mem:schedule-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("10").load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      sql.execute(
          "insert into sync_tasks(id,name,state,schedule_enabled,cron,timezone) values(10,'existing','READY',true,'15 0 2 * * MON-FRI','Asia/Shanghai')");
      Flyway.configure().dataSource(url, "sa", "").load().migrate();
      try (var row = sql.executeQuery("select * from sync_tasks where id=10")) {
        assertTrue(row.next());
        assertTrue(row.getBoolean("schedule_enabled"));
        assertEquals("15 0 2 * * MON-FRI", row.getString("cron"));
        assertEquals("Asia/Shanghai", row.getString("timezone"));
        assertNull(row.getString("schedule_json"));
      }
    }
  }
}
