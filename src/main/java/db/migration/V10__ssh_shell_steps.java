package db.migration;

import java.sql.Connection;
import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * @description 扩展服务器协议与流程组件白名单，兼容 H2 为历史匿名约束生成的不同名称
 * @author Freddy
 * @date 2026-11-13
 */
public class V10__ssh_shell_steps extends BaseJavaMigration {
  /**
   * @description 仅替换两个类型约束，保留已有配置、运行记录与其他约束
   * @author Freddy
   * @date 2026-11-13
   * @param context Flyway 迁移连接
   * @throws Exception 元数据读取或约束更新失败时抛出
   */
  @Override
  public void migrate(Context context) throws Exception {
    extend(
        context.getConnection(),
        "MANAGED_SERVERS",
        "TYPE",
        "ck_managed_server_protocol",
        "'HTTP','SFTP','SSH'");
    extend(
        context.getConnection(),
        "SYNC_TASK_STEPS",
        "COMPONENT_TYPE",
        "ck_sync_step_component",
        "'KEYSTORE','TRUSTSTORE','SFTP','HTTP','SSH'");
  }

  /**
   * @description 通过元数据定位历史类型白名单，替换为包含 SSH 的具名约束
   * @author Freddy
   * @date 2026-11-13
   * @param connection 数据库连接
   * @param table 固定目标表名
   * @param column 固定类型列名
   * @param name 新约束名
   * @param allowed 固定允许值 SQL 列表
   * @throws Exception SQL 操作失败或历史约束缺失时抛出
   */
  private void extend(
      Connection connection, String table, String column, String name, String allowed)
      throws Exception {
    var names = new ArrayList<String>();
    try (var query =
        connection.prepareStatement(
            "SELECT t.CONSTRAINT_NAME,c.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA=CURRENT_SCHEMA AND t.TABLE_NAME=? AND t.CONSTRAINT_TYPE='CHECK'")) {
      query.setString(1, table);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String clause = rows.getString(2);
          if (clause.contains("\"" + column + "\"")
              && clause.contains("'HTTP'")
              && clause.contains("'SFTP'")) names.add(rows.getString(1));
        }
      }
    }
    if (names.isEmpty()) throw new IllegalStateException("未找到类型白名单约束：" + table);
    try (var statement = connection.createStatement()) {
      for (String old : names)
        statement.execute(
            "ALTER TABLE " + table + " DROP CONSTRAINT \"" + old.replace("\"", "\"\"") + "\"");
      statement.execute(
          "ALTER TABLE "
              + table
              + " ADD CONSTRAINT "
              + name
              + " CHECK("
              + column
              + " IN("
              + allowed
              + "))");
    }
  }
}
