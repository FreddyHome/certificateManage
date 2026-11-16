package com.certmanage.sync;

import static com.certmanage.common.Errors.require;
import static com.certmanage.sync.SyncData.*;

import com.certmanage.common.ConfigSecrets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 集中查询任务和执行历史，列表一次读取最近状态，对外详情隐藏秘密引用和解析数据。
 * @author Freddy
 * @date 2026-11-12
 */
class WorkflowQueries {
  private final JdbcTemplate db;
  private final ConfigSecrets secrets;
  private final Function<String, Map<String, Object>> decode;

  /**
   * @description 集中查询任务和执行历史，列表一次读取最近状态，对外详情隐藏秘密引用和解析数据。
   * @author Freddy
   * @date 2026-11-12
   * @param db 同步数据访问入口
   * @param secrets 秘密版本读取与保存服务
   * @param decode 沿用服务JSON读取错误语义的解析函数
   */
  WorkflowQueries(
      JdbcTemplate db, ConfigSecrets secrets, Function<String, Map<String, Object>> decode) {
    this.db = db;
    this.secrets = secrets;
    this.decode = decode;
  }

  /**
   * @description 映射任务及业务计划摘要，旧记录仅做无损兼容解析并保留原调度字段。
   * @author Freddy
   * @date 2026-11-12
   * @param r 当前查询结果行
   * @param n 结果行索引
   * @return 任务基础字段映射
   * @throws SQLException 结果行读取失败时传播
   */
  private Map<String, Object> taskRow(ResultSet r, int n) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.getLong("id"));
    m.put("name", r.getString("name"));
    m.put("description", r.getString("description"));
    m.put("revision", r.getLong("config_revision"));
    m.put("state", r.getString("state"));
    m.put("scheduleEnabled", r.getBoolean("schedule_enabled"));
    m.put("cron", r.getString("cron"));
    m.put("timezone", r.getString("timezone"));
    String scheduleJson = r.getString("schedule_json");
    var schedule =
        scheduleJson == null
            ? SchedulePlan.legacy(r.getString("cron"), r.getString("timezone"))
            : SchedulePlan.normalize(decode.apply(scheduleJson));
    m.put("schedule", schedule.plan());
    m.put(
        "scheduleSummary", schedule.summary() + (r.getBoolean("schedule_enabled") ? "" : "（未启用）"));
    m.put(
        "nextFireAt",
        r.getObject("next_fire_at") == null
            ? null
            : r.getObject("next_fire_at", OffsetDateTime.class).toString());
    return m;
  }

  /**
   * @description 按任务主键倒序查询未删除任务，单次SQL附带最大执行主键对应的状态，无执行时状态为空。
   * @author Freddy
   * @date 2026-11-12
   * @return 未删除任务倒序列表，lastStatus允许为空
   */
  public List<Map<String, Object>> list() {
    return db.query(
        "select t.*, r.status as last_status from sync_tasks t left join sync_runs r on r.id=(select max(latest.id) from sync_runs latest where latest.task_id=t.id) where t.deleted_at is null order by t.id desc",
        (r, n) -> {
          var task = taskRow(r, n);
          task.put("lastStatus", r.getString("last_status"));
          return task;
        });
  }

  /**
   * @description 读取未删除任务与有序步骤，保留内部秘密引用供保存和快照使用。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   * @return 包含内部步骤和秘密引用的任务
   * @throws IllegalArgumentException 任务不存在或已删除时抛出
   */
  Map<String, Object> stored(long id) {
    var rows =
        db.query("select * from sync_tasks where id=? and deleted_at is null", this::taskRow, id);
    require(!rows.isEmpty(), "同步任务不存在");
    var task = rows.getFirst();
    task.put(
        "steps",
        db.query(
            "select * from sync_task_steps where task_id=? order by position",
            (r, n) -> {
              var step = new LinkedHashMap<String, Object>();
              step.put("key", r.getString("step_key"));
              step.put("name", r.getString("name"));
              step.put("type", r.getString("component_type"));
              step.put("config", decode.apply(r.getString("config_json")));
              step.put("secretId", r.getObject("secret_bundle_id"));
              return step;
            },
            id));
    return task;
  }

  /**
   * @description 读取任务详情，隐藏秘密引用、证书引用和解析结果，仅提供秘密是否配置的标识。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   * @return 已隐藏秘密引用和内部解析字段的任务详情
   * @throws IllegalArgumentException 任务不存在或已删除时抛出
   */
  public Map<String, Object> detail(long id) {
    var task = stored(id);
    var visible = new ArrayList<Map<String, Object>>();
    for (var step : maps(task.get("steps"))) {
      var secret =
          secrets.read(
              step.get("secretId") == null ? null : ((Number) step.get("secretId")).longValue());
      step.remove("secretId");
      var c = map(step.get("config"));
      c.remove("references");
      c.remove("resolvedEntries");
      c.remove("resolvedBody");
      c.remove("resolvedAssetId");
      c.put("passwordConfigured", secret.containsKey("password"));
      c.put("keyPasswordConfigured", secret.containsKey("keyPassword"));
      step.put("config", c);
      visible.add(step);
    }
    task.put("steps", visible);
    return task;
  }

  /**
   * @description 确认任务未删除后返回最近两百条执行记录，按执行主键倒序。
   * @author Freddy
   * @date 2026-11-12
   * @param task 任务主键
   * @return 最近两百条执行记录，无记录时为空列表
   * @throws IllegalArgumentException 任务不存在或已删除时抛出
   */
  public List<Map<String, Object>> runs(long task) {
    stored(task);
    return db.query(
        "select * from sync_runs where task_id=? order by id desc fetch first 200 rows only",
        this::runRow,
        task);
  }

  /**
   * @description 映射执行记录字段和可空时间，保持历史响应名称。
   * @author Freddy
   * @date 2026-11-12
   * @param r 当前查询结果行
   * @param n 结果行索引
   * @return 执行记录字段映射
   * @throws SQLException 结果行读取失败时传播
   */
  private Map<String, Object> runRow(ResultSet r, int n) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    for (String field : List.of("id", "status")) m.put(field, r.getObject(field));
    m.put("taskId", r.getLong("task_id"));
    m.put("triggerType", r.getString("trigger_type"));
    m.put("revision", r.getLong("task_revision"));
    m.put("errorSummary", r.getString("error_summary"));
    for (String field : List.of("created_at", "started_at", "finished_at")) {
      String name =
          switch (field) {
            case "created_at" -> "createdAt";
            case "started_at" -> "startedAt";
            default -> "finishedAt";
          };
      m.put(
          name,
          r.getObject(field) == null ? null : r.getObject(field, OffsetDateTime.class).toString());
    }
    return m;
  }

  /**
   * @description 读取执行记录及按位置排序的步骤，保留结果JSON和可空开始结束时间。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   * @return 包含有序步骤的执行详情
   * @throws IllegalArgumentException 执行记录不存在时抛出
   */
  public Map<String, Object> runDetail(long id) {
    var rows = db.query("select * from sync_runs where id=?", this::runRow, id);
    require(!rows.isEmpty(), "执行记录不存在");
    var run = rows.getFirst();
    run.put(
        "steps",
        db.query(
            "select * from sync_run_steps where run_id=? order by position",
            (r, n) -> {
              var m = new LinkedHashMap<String, Object>();
              m.put("key", r.getString("step_key"));
              m.put("name", r.getString("name"));
              m.put("type", r.getString("component_type"));
              m.put("status", r.getString("status"));
              m.put("errorSummary", r.getString("error_summary"));
              m.put(
                  "result",
                  r.getString("result_json") == null
                      ? null
                      : decode.apply(r.getString("result_json")));
              m.put(
                  "startedAt",
                  r.getObject("started_at") == null
                      ? null
                      : r.getObject("started_at", OffsetDateTime.class).toString());
              m.put(
                  "finishedAt",
                  r.getObject("finished_at") == null
                      ? null
                      : r.getObject("finished_at", OffsetDateTime.class).toString());
              return m;
            },
            id));
    return run;
  }
}
