package com.certmanage.server;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * @description 验证既有版本数据库升级后保留 SFTP 服务器和任务并支持独立 SSH 类型
 * @author Freddy
 * @date 2026-11-13
 */
class SshSchemaMigrationTest {
  /**
   * @description 从 V9 升级至 V10，检查旧记录保留、SSH 可写以及白名单未被移除
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 数据库初始化或迁移失败时抛出
   */
  @Test
  void upgradesExistingDatabaseWithoutLosingDataOrTypeConstraints() throws Exception {
    String url = "jdbc:h2:mem:migration" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("9").load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      sql.execute(
          "insert into managed_servers(id,alias,alias_key,type,enabled,config_revision) values(10,'existing','existing','SFTP',true,1)");
      sql.execute(
          "insert into sync_tasks(id,name,state,cron,timezone) values(10,'existing','READY','0 0 2 * * *','Asia/Shanghai')");
      sql.execute(
          "insert into sync_task_steps(task_id,step_key,position,component_type,name,config_json) values(10,'existing',0,'SFTP','existing','{}')");
      Flyway.configure().dataSource(url, "sa", "").load().migrate();
      try (var row = sql.executeQuery("select type from managed_servers where id=10")) {
        assertTrue(row.next());
        assertEquals("SFTP", row.getString(1));
      }
      try (var row =
          sql.executeQuery("select component_type from sync_task_steps where task_id=10")) {
        assertTrue(row.next());
        assertEquals("SFTP", row.getString(1));
      }
      sql.execute(
          "insert into managed_servers(alias,alias_key,type,enabled,config_revision) values('shell','shell','SSH',true,1)");
      sql.execute(
          "insert into sync_task_steps(task_id,step_key,position,component_type,name,config_json) values(10,'shell',1,'SSH','shell','{}')");
      assertThrows(
          java.sql.SQLException.class,
          () ->
              sql.execute(
                  "insert into managed_servers(alias,alias_key,type,enabled,config_revision) values('bad','bad','FTP',true,1)"));
      assertThrows(
          java.sql.SQLException.class,
          () ->
              sql.execute(
                  "insert into sync_task_steps(task_id,step_key,position,component_type,name,config_json) values(10,'bad',2,'FTP','bad','{}')"));
    }
  }
}
