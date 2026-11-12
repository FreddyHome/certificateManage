package com.certmanage.sync;

import com.certmanage.common.ExecutionLog;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 推进到期任务的下次触发时间，再单次尝试入队；业务拒绝保留失败记录，沿用独立事务边界。
 * @author Freddy
 * @date 2026-11-12
 */
class WorkflowScheduler {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final WorkflowService workflows;
  private final ExecutionLog logs;

  /**
   * @description 推进到期任务的下次触发时间，再单次尝试入队；业务拒绝保留失败记录，沿用独立事务边界。
   * @author Freddy
   * @date 2026-11-12
   * @param db 同步数据访问入口
   * @param tx 沿用调用方配置的事务模板
   * @param workflows 提供全局锁和原有入队事务的服务
   * @param logs 执行日志服务
   */
  WorkflowScheduler(
      JdbcTemplate db, TransactionTemplate tx, WorkflowService workflows, ExecutionLog logs) {
    this.db = db;
    this.tx = tx;
    this.workflows = workflows;
    this.logs = logs;
  }

  /**
   * @description 扫描到期任务，在锁内推进下次触发时间后单次入队；拒绝时记录失败执行，不重试远端操作。
   * @author Freddy
   * @date 2026-11-12
   */
  public void schedule() {
    var due =
        db.queryForList(
            "select id from sync_tasks where deleted_at is null and schedule_enabled=true and next_fire_at<=current_timestamp");
    for (var row : due) {
      long id = ((Number) row.get("ID")).longValue();
      OffsetDateTime fire =
          tx.execute(
              s -> {
                workflows.lock();
                var current = db.queryForMap("select * from sync_tasks where id=?", id);
                if (!(Boolean) current.get("SCHEDULE_ENABLED")
                    || current.get("DELETED_AT") != null
                    || current.get("NEXT_FIRE_AT") == null
                    || ((OffsetDateTime) current.get("NEXT_FIRE_AT")).isAfter(OffsetDateTime.now()))
                  return null;
                var scheduled = (OffsetDateTime) current.get("NEXT_FIRE_AT");
                var next =
                    CronExpression.parse((String) current.get("CRON"))
                        .next(ZonedDateTime.now(ZoneId.of((String) current.get("TIMEZONE"))));
                db.update(
                    "update sync_tasks set next_fire_at=? where id=?",
                    next == null ? null : next.toOffsetDateTime(),
                    id);
                return scheduled;
              });
      if (fire == null) continue;
      try {
        workflows.enqueue(id, null, null, fire);
      } catch (IllegalArgumentException failure) {
        tx.executeWithoutResult(
            s -> {
              workflows.lock();
              if (db.queryForObject(
                      "select count(*) from sync_runs where task_id=? and scheduled_for=?",
                      Integer.class,
                      id,
                      fire)
                  > 0) return;
              var task = workflows.stored(id);
              db.update(
                  "insert into sync_runs(task_id,trigger_type,scheduled_for,task_revision,status,snapshot_json,finished_at,error_summary) values(?,'SCHEDULED',?,?,'FAILED',?,current_timestamp,?)",
                  id,
                  fire,
                  task.get("revision"),
                  workflows.encode(Map.of("steps", List.of())),
                  "本次定时执行未入队：任务重叠或当前证书/连接配置不可用");
              long failedRun =
                  db.queryForObject(
                      "select id from sync_runs where task_id=? and scheduled_for=?",
                      Long.class,
                      id,
                      fire);
              logs.append(
                  "SYNC",
                  "SCHEDULE_REJECTED",
                  "FAILURE",
                  "scheduler",
                  failedRun,
                  null,
                  "SYNC_TASK",
                  id,
                  "定时任务未入队：重叠或配置不可用",
                  Map.of());
            });
      }
    }
  }
}
