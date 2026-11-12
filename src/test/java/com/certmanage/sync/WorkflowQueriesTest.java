package com.certmanage.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.ConfigSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * @description 使用独立内存库验证同步查询顺序、最近状态与固定数据库访问次数。
 * @author Freddy
 * @date 2026-11-12
 */
class WorkflowQueriesTest {
  private DriverManagerDataSource source;
  private JdbcTemplate db;
  private WorkflowService workflows;

  /**
   * @description 创建独立任务与执行记录表，不连接应用运行数据库。
   * @author Freddy
   * @date 2026-11-12
   */
  @BeforeEach
  void setUp() {
    source =
        spy(
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    db = new JdbcTemplate(source);
    db.execute(
        "create table sync_tasks(id bigint primary key,name varchar,description varchar,config_revision bigint,state varchar,schedule_enabled boolean,cron varchar,timezone varchar,next_fire_at timestamp with time zone,deleted_at timestamp with time zone)");
    db.execute(
        "create table sync_runs(id bigint primary key,task_id bigint,status varchar,created_at timestamp with time zone)");
    db.execute(
        "create table sync_task_steps(task_id bigint,position int,step_key varchar,name varchar,component_type varchar,config_json varchar,secret_bundle_id bigint)");
    workflows = new WorkflowService(db, null, new ObjectMapper(), null, null, null, null, null);
  }

  /**
   * @description 最近状态必须按执行主键选择，任务倒序且没有执行记录的任务保留空状态。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void listsDescendingTasksWithLatestRunIdAndNullForUnrun() {
    for (int id = 1; id <= 4; id++) {
      insertTask(id);
    }
    db.update("update sync_tasks set deleted_at=current_timestamp where id=4");
    db.update(
        "insert into sync_runs values(10,1,'FAILED',current_timestamp),(11,1,'SUCCESS',timestamp with time zone '2000-01-01 00:00:00+00'),(12,2,'RUNNING',current_timestamp)");
    var tasks = workflows.list();
    assertThat(tasks).extracting(t -> t.get("id")).containsExactly(3L, 2L, 1L);
    assertThat(tasks.get(0)).containsEntry("lastStatus", null);
    assertThat(tasks.get(1)).containsEntry("lastStatus", "RUNNING");
    assertThat(tasks.get(2)).containsEntry("lastStatus", "SUCCESS");
  }

  /**
   * @description 列表增加任务数量时数据库访问次数仍固定为一次，阻止逐任务状态查询回归。
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception 数据源连接验证失败时传播
   */
  @Test
  void listsManyTasksWithOneDatabaseConnection() throws Exception {
    for (int id = 1; id <= 30; id++) {
      insertTask(id);
    }
    clearInvocations(source);
    assertThat(workflows.list()).hasSize(30);
    verify(source, times(1)).getConnection();
  }

  /**
   * @description 空任务库返回空列表，缺失或已删除任务保留原错误信息。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void preservesEmptyListAndMissingTaskErrors() {
    assertThat(workflows.list()).isEmpty();
    assertThatThrownBy(() -> workflows.detail(99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("同步任务不存在");
    assertThatThrownBy(() -> workflows.runs(99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("同步任务不存在");
    assertThatThrownBy(() -> workflows.runDetail(99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("执行记录不存在");
    insertTask(1);
    db.update("update sync_tasks set deleted_at=current_timestamp where id=1");
    assertThatThrownBy(() -> workflows.stored(1)).hasMessage("同步任务不存在");
  }

  /**
   * @description 内部查询保留快照所需秘密引用，而详情只展示配置标记并移除内部证书解析字段。
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void keepsStoredSecretReferencesButRedactsVisibleDetail() {
    insertTask(1);
    db.update(
        "insert into sync_task_steps values(1,1,'upload','上传','SFTP',?,9)",
        "{\"host\":\"example.test\",\"references\":[{}],\"resolvedEntries\":[{}]}");
    var secrets = mock(ConfigSecrets.class);
    when(secrets.read(9L)).thenReturn(new LinkedHashMap<>(Map.of("password", "hidden")));
    workflows = new WorkflowService(db, null, new ObjectMapper(), secrets, null, null, null, null);

    var storedStep = SyncData.maps(workflows.stored(1).get("steps")).getFirst();
    assertThat(storedStep).containsEntry("secretId", 9L);
    assertThat(SyncData.map(storedStep.get("config")))
        .containsKeys("references", "resolvedEntries");

    var visibleStep = SyncData.maps(workflows.detail(1).get("steps")).getFirst();
    assertThat(visibleStep).doesNotContainKey("secretId");
    assertThat(SyncData.map(visibleStep.get("config")))
        .containsEntry("host", "example.test")
        .containsEntry("passwordConfigured", true)
        .containsEntry("keyPasswordConfigured", false)
        .doesNotContainKeys("password", "keyPassword", "references", "resolvedEntries");
  }

  /**
   * @description 插入无调度任务用于查询测试。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务主键
   */
  private void insertTask(long id) {
    db.update(
        "insert into sync_tasks(id,name,description,config_revision,state,schedule_enabled,cron,timezone) values(?,?,'',1,'DRAFT',false,'0 0 2 * * *','Asia/Shanghai')",
        id,
        "任务" + id);
  }
}
