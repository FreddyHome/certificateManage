package com.certmanage.sync;

import static com.certmanage.common.Errors.require;
import static com.certmanage.sync.SyncData.*;

import com.certmanage.common.*;
import com.certmanage.server.ServerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * @description 装配同步查询与调度职责，保留任务保存、快照入队、全局锁与原事务边界。
 * @author Freddy
 * @date 2026-11-12
 */
@Service
public class WorkflowService {
  private final WorkflowQueries queries;
  private final WorkflowScheduler scheduler;
  private final ExecutionLog logs;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final ConfigSecrets secrets;
  private final Audit audit;
  private final WorkflowValidator validator;
  private final ServerService servers;

  /**
   * @description 装配同步查询与调度职责，保留任务保存、快照入队、全局锁与原事务边界。
   * @author Freddy
   * @date 2026-11-12
   * @param db 同步数据访问入口
   * @param tx 沿用调用方配置的事务模板
   * @param json 配置JSON序列化器
   * @param secrets 秘密版本读取与保存服务
   * @param audit 操作审计服务
   * @param validator 流程配置校验器
   * @param servers 服务器快照服务
   * @param logs 执行日志服务
   */
  public WorkflowService(
      JdbcTemplate db,
      TransactionTemplate tx,
      ObjectMapper json,
      ConfigSecrets secrets,
      Audit audit,
      WorkflowValidator validator,
      ServerService servers,
      ExecutionLog logs) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.secrets = secrets;
    this.audit = audit;
    this.validator = validator;
    this.servers = servers;
    this.logs = logs;
    this.queries = new WorkflowQueries(db, secrets, this::decode);
    this.scheduler = new WorkflowScheduler(db, tx, this, logs);
  }

  /**
   * @description 获取同步全局行锁，调用方必须已开启事务以保护任务配置和执行队列。
   * @author Freddy
   * @date 2026-11-12
   */
  void lock() {
    db.queryForObject("select id from sync_guard where id=1 for update", Integer.class);
  }

  /**
   * @description 将配置或快照序列化为JSON，保持原有序列化失败错误语义。
   * @author Freddy
   * @date 2026-11-12
   * @param v 待写入的配置或快照
   * @return 序列化后的JSON字符串
   * @throws IllegalArgumentException JSON序列化失败时抛出
   */
  String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("流程配置无法序列化");
    }
  }

  /**
   * @description 读取配置或快照JSON，保持原有反序列化失败错误语义。
   * @author Freddy
   * @date 2026-11-12
   * @param s 待读取的JSON字符串
   * @return 解析后的配置映射
   * @throws IllegalStateException JSON读取失败时抛出
   */
  Map<String, Object> decode(String s) {
    try {
      return json.readValue(
          s, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("流程配置无法读取");
    }
  }

  /**
   * @description 在原事务和全局锁内校验修订号、保留未替换秘密并保存有序步骤及审计记录。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   * @param input 任务配置及更新时的原修订号
   * @param user 操作者名称，定时入队时可为空
   * @return 保存后可展示的任务详情
   * @throws ResponseStatusException 更新时配置修订号冲突时抛出
   * @throws IllegalArgumentException 任务字段或步骤配置校验失败时抛出
   */
  public Map<String, Object> save(Long id, Map<String, Object> input, String user) {
    return tx.execute(
        status -> {
          lock();
          Map<String, Object> old = id == null ? Map.of() : stored(id);
          if (id != null
              && (!Objects.equals(
                  ((Number) old.get("revision")).longValue(),
                  input.get("revision") instanceof Number n ? n.longValue() : -1L)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已修改，请刷新后重试");
          String name = str(input, "name", "").trim(), description = str(input, "description", "");
          require(
              !name.isEmpty() && name.length() <= 200 && description.length() <= 2000,
              "任务名称必填，名称或描述过长");
          String state = str(input, "state", "DRAFT");
          require(Set.of("DRAFT", "READY").contains(state), "任务状态错误");
          boolean calendar =
              input.get("schedule") instanceof Map<?, ?> s && "CALENDAR".equals(s.get("frequency"));
          boolean enabled =
              calendar ? state.equals("READY") : Boolean.TRUE.equals(input.get("scheduleEnabled"));
          require(!enabled || state.equals("READY"), "草稿不能启用定时");
          var schedule =
              input.containsKey("schedule")
                  ? SchedulePlan.normalize(map(input.get("schedule")))
                  : SchedulePlan.legacy(
                      str(input, "cron", str(old, "cron", "0 0 2 * * *")),
                      str(input, "timezone", str(old, "timezone", "Asia/Shanghai")));
          String cron = schedule.cron(), zone = schedule.timezone();
          var next = validator.nextTimes(cron, zone);
          var oldByKey = new HashMap<String, Map<String, Object>>();
          for (var step : maps(old.get("steps"))) oldByKey.put(str(step, "key", ""), step);
          var steps = new ArrayList<Map<String, Object>>();
          for (var supplied : maps(input.get("steps"))) {
            var step = new LinkedHashMap<String, Object>();
            String key = str(supplied, "key", UUID.randomUUID().toString());
            step.put("key", key);
            step.put("name", str(supplied, "name", str(supplied, "type", "")));
            step.put("type", str(supplied, "type", ""));
            var c = map(supplied.get("config"));
            var oldStep = oldByKey.get(key);
            Long secretId =
                oldStep == null || oldStep.get("secretId") == null
                    ? null
                    : ((Number) oldStep.get("secretId")).longValue();
            var secret = new LinkedHashMap<>(secrets.read(secretId));
            boolean changed = false;
            for (String field : List.of("password", "keyPassword")) {
              if (c.containsKey(field)) {
                String value = str(c, field, "");
                if (!value.isEmpty()) {
                  secret.put(field, value);
                  changed = true;
                }
                c.remove(field);
              }
              c.remove(field + "Configured");
            }
            if (changed) secretId = secrets.save(secret);
            step.put("secretId", secretId);
            step.put("config", c);
            steps.add(step);
          }
          validator.prepare(steps, state.equals("READY"));
          long actor = audit.actor(user);
          if (id == null) {
            db.update(
                "insert into sync_tasks(name,description,state,schedule_enabled,cron,timezone,schedule_json,next_fire_at,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?)",
                name,
                description,
                state,
                enabled,
                cron,
                zone,
                encode(schedule.plan()),
                enabled ? ZonedDateTime.parse(next.getFirst()).toOffsetDateTime() : null,
                actor,
                actor);
          } else
            db.update(
                "update sync_tasks set name=?,description=?,state=?,schedule_enabled=?,cron=?,timezone=?,schedule_json=?,next_fire_at=?,config_revision=config_revision+1,updated_by=?,updated_at=current_timestamp where id=?",
                name,
                description,
                state,
                enabled,
                cron,
                zone,
                encode(schedule.plan()),
                enabled ? ZonedDateTime.parse(next.getFirst()).toOffsetDateTime() : null,
                actor,
                id);
          long taskId =
              id == null ? db.queryForObject("select max(id) from sync_tasks", Long.class) : id;
          clearSteps(taskId);
          var ids = new HashMap<String, Long>();
          int position = 0;
          for (var step : steps) {
            var c = map(step.get("config"));
            db.update(
                "insert into sync_task_steps(task_id,step_key,position,component_type,name,config_json,secret_bundle_id) values(?,?,?,?,?,?,?)",
                taskId,
                step.get("key"),
                ++position,
                step.get("type"),
                step.get("name"),
                encode(c),
                step.get("secretId"));
            long stepId =
                db.queryForObject(
                    "select id from sync_task_steps where task_id=? and step_key=?",
                    Long.class,
                    taskId,
                    step.get("key"));
            ids.put((String) step.get("key"), stepId);
            if (c.get("serverId") != null)
              db.update("insert into sync_step_servers values(?,?)", stepId, id(c, "serverId"));
            if (BusinessComponents.matches(c) && c.get("sourceServerId") != null)
              db.update(
                  "merge into sync_step_servers key(step_id,server_id) values(?,?)",
                  stepId,
                  id(c, "sourceServerId"));
            for (var ref : maps(c.get("references")))
              db.update(
                  "insert into sync_step_certificates values(?,?,?,?,?)",
                  stepId,
                  id(ref, "assetId"),
                  id(ref, "materialId"),
                  ref.get("alias"),
                  ref.get("role"));
            String source = str(c, "sourceStep", "");
            if (!source.isBlank())
              db.update("insert into sync_step_inputs values(?,?)", stepId, ids.get(source));
          }
          audit.record(user, "SYNC_TASK_SAVE", "SYNC_TASK", taskId);
          return detail(taskId);
        });
  }

  /**
   * @description 先清理步骤依赖再删除任务步骤，保留外键约束要求的删除顺序。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   */
  private void clearSteps(long id) {
    for (String table : List.of("sync_step_inputs", "sync_step_certificates", "sync_step_servers"))
      db.update(
          "delete from "
              + table
              + " where step_id in(select id from sync_task_steps where task_id=?)",
          id);
    db.update("delete from sync_task_steps where task_id=?", id);
  }

  /**
   * @description 校验已保存任务并返回下次触发时间和解析出的证书条目。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   * @return 校验成功标记、下次触发时间和解析条目
   * @throws IllegalArgumentException 任务不存在或配置校验失败时抛出
   */
  public Map<String, Object> validate(long id) {
    var task = stored(id);
    var steps = maps(task.get("steps"));
    validator.prepare(steps, true);
    return Map.of(
        "valid",
        true,
        "nextTimes",
        validator.nextTimes(str(task, "cron", ""), str(task, "timezone", "")),
        "entries",
        steps.stream()
            .filter(s -> map(s.get("config")).containsKey("resolvedEntries"))
            .map(
                s ->
                    Map.of(
                        "step",
                        s.get("name"),
                        "entries",
                        map(s.get("config")).get("resolvedEntries")))
            .toList());
  }

  /**
   * @description 在原事务和全局锁内阻止删除执行中的任务，并软删除任务及记录审计。
   * @author Freddy
   * @date 2026-11-12
   * @param id 任务或执行记录主键
   * @param user 操作者名称，定时入队时可为空
   * @throws IllegalArgumentException 任务不存在或仍在执行时抛出
   */
  public void delete(long id, String user) {
    tx.executeWithoutResult(
        s -> {
          lock();
          stored(id);
          require(
              db.queryForObject(
                      "select count(*) from sync_runs where task_id=? and status in('QUEUED','RUNNING')",
                      Integer.class,
                      id)
                  == 0,
              "任务执行中，不能删除");
          db.update(
              "update sync_tasks set deleted_at=current_timestamp,schedule_enabled=false,next_fire_at=null where id=?",
              id);
          audit.record(user, "SYNC_TASK_DELETE", "SYNC_TASK", id);
        });
  }

  /**
   * @description 在原事务与全局锁内幂等入队，固定任务配置、服务器、证书和秘密版本快照。
   * @author Freddy
   * @date 2026-11-12
   * @param taskId 任务主键
   * @param requestKey 请求幂等标识，可为空
   * @param user 操作者名称，定时入队时可为空
   * @return 已存在或新创建的执行记录主键
   * @throws IllegalArgumentException 任务不可执行、请求标识过长、队列已满或配置不可用时抛出
   */
  public long enqueue(long taskId, String requestKey, String user) {
    return enqueue(taskId, requestKey, user, null);
  }

  /**
   * @description 在原事务与全局锁内幂等入队，固定任务配置、服务器、证书和秘密版本快照。
   * @author Freddy
   * @date 2026-11-12
   * @param taskId 任务主键
   * @param requestKey 请求幂等标识，可为空
   * @param user 操作者名称，定时入队时可为空
   * @param scheduled 计划触发时间，手工入队时为空
   * @return 已存在或新创建的执行记录主键
   * @throws IllegalArgumentException 任务不可执行、请求标识过长、队列已满或配置不可用时抛出
   */
  long enqueue(long taskId, String requestKey, String user, OffsetDateTime scheduled) {
    return tx.execute(
        s -> {
          lock();
          var task = stored(taskId);
          if (requestKey != null) {
            require(requestKey.length() <= 100, "请求标识过长");
            var prior =
                db.queryForList(
                    "select id from sync_runs where task_id=? and request_key=?",
                    Long.class,
                    taskId,
                    requestKey);
            if (!prior.isEmpty()) return prior.getFirst();
          }
          require("READY".equals(task.get("state")), "请先校验并保存为可执行任务");
          require(
              db.queryForObject(
                      "select count(*) from sync_runs where task_id=? and status in('QUEUED','RUNNING')",
                      Integer.class,
                      taskId)
                  == 0,
              "已有执行中的任务，请等待完成");
          require(
              db.queryForObject(
                      "select count(*) from sync_runs where status='QUEUED'", Integer.class)
                  < 100,
              "等待执行任务过多，请稍后重试");
          var steps = maps(task.get("steps"));
          validator.prepare(steps, true);
          var serverSnapshots = new HashMap<Long, Map<String, Object>>();
          for (var step : steps) {
            var c = map(step.get("config"));
            if (c.get("serverId") != null) {
              long server = id(c, "serverId");
              var snapshot = serverSnapshots.computeIfAbsent(server, servers::snapshot);
              step.put("serverSnapshot", snapshot);
            }
            if (BusinessComponents.matches(c)
                && c.get("sourceServerId") != null
                && !"TN_CERTIFICATE".equals(c.get("businessComponent"))) {
              long source = id(c, "sourceServerId");
              var snapshot = serverSnapshots.computeIfAbsent(source, servers::snapshot);
              step.put("sourceServerSnapshot", snapshot);
            }
          }
          task.put("steps", steps);
          task.put("requestedBy", user == null ? "scheduler" : user);
          db.update(
              "insert into sync_runs(task_id,trigger_type,scheduled_for,request_key,task_revision,status,snapshot_json) values(?,?,?,?,?,'QUEUED',?)",
              taskId,
              scheduled == null ? "MANUAL" : "SCHEDULED",
              scheduled,
              requestKey,
              task.get("revision"),
              encode(task));
          long run = db.queryForObject("select max(id) from sync_runs", Long.class);
          var secretRefs = new LinkedHashSet<Long>();
          var certificateRefs = new LinkedHashSet<List<Long>>();
          for (var step : steps) {
            if (step.get("secretId") != null)
              secretRefs.add(((Number) step.get("secretId")).longValue());
            for (var ref : maps(map(step.get("config")).get("references")))
              certificateRefs.add(List.of(id(ref, "assetId"), id(ref, "materialId")));
          }
          var serverRows = new ArrayList<Object[]>();
          var configIds = new LinkedHashSet<Long>();
          for (var snapshot : serverSnapshots.values()) {
            long config = id(snapshot, "configId");
            configIds.add(config);
            serverRows.add(new Object[] {run, id(snapshot, "id"), config});
          }
          if (!configIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(configIds.size(), "?"));
            secretRefs.addAll(
                db.queryForList(
                    "select secret_bundle_id from managed_server_configs where id in ("
                        + placeholders
                        + ") and secret_bundle_id is not null",
                    Long.class,
                    configIds.toArray()));
            for (var ref :
                db.queryForList(
                    "select source_asset_id,material_id from managed_server_trust_certificates where server_config_id in ("
                        + placeholders
                        + ")",
                    configIds.toArray())) {
              certificateRefs.add(
                  List.of(
                      ((Number) ref.get("SOURCE_ASSET_ID")).longValue(),
                      ((Number) ref.get("MATERIAL_ID")).longValue()));
            }
          }
          if (!serverRows.isEmpty())
            db.batchUpdate("insert into sync_run_servers values(?,?,?)", serverRows);
          if (!secretRefs.isEmpty())
            db.batchUpdate(
                "insert into sync_run_secret_bundles values(?,?)",
                secretRefs.stream().map(secret -> new Object[] {run, secret}).toList());
          if (!certificateRefs.isEmpty())
            db.batchUpdate(
                "insert into sync_run_certificates(run_id,source_asset_id,material_id,fingerprint) select ?,?,?,fingerprint_sha256 from certificate_materials where id=?",
                certificateRefs.stream()
                    .map(ref -> new Object[] {run, ref.get(0), ref.get(1), ref.get(1)})
                    .toList());
          var stepRows = new ArrayList<Object[]>();
          int p = 0;
          for (var step : steps)
            stepRows.add(
                new Object[] {run, step.get("key"), ++p, step.get("type"), step.get("name")});
          if (!stepRows.isEmpty())
            db.batchUpdate(
                "insert into sync_run_steps(run_id,step_key,position,component_type,name) values(?,?,?,?,?)",
                stepRows);
          if (user != null) audit.record(user, "SYNC_EXECUTE", "SYNC_RUN", run);
          logs.append(
              "SYNC",
              "RUN_QUEUED",
              "INFO",
              user,
              run,
              null,
              "SYNC_TASK",
              taskId,
              "同步任务已入队",
              Map.of(
                  "triggerType",
                  scheduled == null ? "MANUAL" : "SCHEDULED",
                  "revision",
                  task.get("revision")));
          return run;
        });
  }

  /**
   * @description 按任务主键倒序查询未删除任务，单次SQL附带最大执行主键对应的状态，无执行时状态为空。
   * @author Freddy
   * @date 2026-11-12
   * @return 未删除任务倒序列表，lastStatus允许为空
   */
  public List<Map<String, Object>> list() {
    return queries.list();
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
    return queries.stored(id);
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
    return queries.detail(id);
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
    return queries.runs(task);
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
    return queries.runDetail(id);
  }

  /**
   * @description 扫描到期任务，在锁内推进下次触发时间后单次入队；拒绝时记录失败执行，不重试远端操作。
   * @author Freddy
   * @date 2026-11-12
   */
  public void schedule() {
    scheduler.schedule();
  }
}
