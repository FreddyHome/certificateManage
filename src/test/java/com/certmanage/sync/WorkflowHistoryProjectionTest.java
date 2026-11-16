package com.certmanage.sync;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * @description 验证历史列表仅检查任务存在，不读取或解析任务步骤。
 * @author Freddy
 * @date 2026-11-16
 */
class WorkflowHistoryProjectionTest {
  /**
   * @description 验证历史查询无需步骤配置与冻结快照字段。
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void historyDoesNotNeedTaskConfigurationOrSnapshotColumns() {
    var db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    db.execute("create table sync_tasks(id bigint,deleted_at timestamp)");
    db.execute(
        "create table sync_runs(id bigint,task_id bigint,status varchar,trigger_type varchar,task_revision bigint,error_summary varchar,created_at timestamp with time zone,started_at timestamp with time zone,finished_at timestamp with time zone)");
    db.update("insert into sync_tasks values(1,null)");
    db.update(
        "insert into sync_runs values(2,1,'SUCCESS','MANUAL',1,null,current_timestamp,null,null)");
    var service = new WorkflowService(db, null, new ObjectMapper(), null, null, null, null, null);
    assertThat(service.runs(1))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", "SUCCESS"));
  }
}
