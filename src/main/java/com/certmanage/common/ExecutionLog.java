package com.certmanage.common;

import static com.certmanage.common.Errors.require;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 保存可检索摘要，过滤前事件另存于加密原始详情。 */
@Service
public class ExecutionLog {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final RawExecutionLog raw;
  private final java.util.Queue<Pending> pending =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  private record Pending(
      OffsetDateTime occurredAt,
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details,
      String trace) {}

  private static final Set<String> FIELDS =
      Set.of(
          "elapsedMs",
          "bytes",
          "entries",
          "filename",
          "statusCode",
          "serverAlias",
          "triggerType",
          "revision",
          "count",
          "attempt",
          "deliveryId",
          "thresholdDays",
          "errorType");

  public ExecutionLog(JdbcTemplate db, ObjectMapper json, RawExecutionLog raw) {
    this.db = db;
    this.json = json;
    this.raw = raw;
  }

  /**
   * A completed remote side effect must never be retried merely because its audit insert failed.
   */
  public void appendAfterEffect(
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details) {
    OffsetDateTime occurredAt = OffsetDateTime.now();
    actor = resolveActor(actor);
    String trace = raw.application(code, message, details, step);
    try {
      insert(
          occurredAt,
          source,
          code,
          outcome,
          actor,
          run,
          step,
          targetType,
          target,
          message,
          details,
          trace);
    } catch (org.springframework.dao.DataAccessException failure) {
      pending.add(
          new Pending(
              occurredAt,
              source,
              code,
              outcome,
              actor,
              run,
              step,
              targetType,
              target,
              message,
              details == null ? Map.of() : new LinkedHashMap<>(details),
              trace));
      org.slf4j.LoggerFactory.getLogger(getClass()).error("已完成操作的审计事件暂未写入，将重试写库，不重放远端操作");
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public synchronized void flushPending() {
    for (int i = 0; i < 200; i++) {
      var event = pending.peek();
      if (event == null) return;
      try {
        insert(
            event.occurredAt,
            event.source,
            event.code,
            event.outcome,
            event.actor,
            event.run,
            event.step,
            event.targetType,
            event.target,
            event.message,
            event.details,
            event.trace);
        pending.remove();
      } catch (org.springframework.dao.DataAccessException unavailable) {
        return;
      }
    }
  }

  public void append(
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details) {
    insert(
        OffsetDateTime.now(),
        source,
        code,
        outcome,
        resolveActor(actor),
        run,
        step,
        targetType,
        target,
        message,
        details,
        raw.application(code, message, details, step));
  }

  private String resolveActor(String actor) {
    if (actor == null || actor.endsWith("-worker")) {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      return authentication != null
              && authentication.isAuthenticated()
              && !"anonymousUser".equals(authentication.getName())
          ? authentication.getName()
          : "system";
    }
    return actor;
  }

  private void insert(
      OffsetDateTime occurredAt,
      String source,
      String code,
      String outcome,
      String actor,
      Long run,
      String step,
      String targetType,
      Long target,
      String message,
      Map<String, ?> details,
      String trace) {
    var safe = new LinkedHashMap<String, Object>();
    if (details != null)
      for (var e : details.entrySet())
        if (FIELDS.contains(e.getKey())
            && (e.getValue() instanceof Number
                || e.getValue() instanceof Boolean
                || e.getValue() instanceof String))
          safe.put(
              e.getKey(),
              e.getValue() instanceof String s
                  ? s.substring(0, Math.min(s.length(), 300))
                  : e.getValue());
    try {
      db.update(
          "insert into execution_logs(occurred_at,source,event_code,outcome,actor_name,run_id,step_key,target_type,target_id,message,detail_json,raw_trace_id) values(?,?,?,?,?,?,?,?,?,?,?,?)",
          occurredAt,
          source,
          code,
          outcome,
          actor == null ? "system" : actor,
          run,
          step,
          targetType,
          target,
          message,
          json.writeValueAsString(safe),
          trace);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("审计日志编码失败", e);
    }
  }

  public Map<String, Object> list(
      String source, Long run, String outcome, String from, String to, int page, int size) {
    require(page >= 1 && page <= 1000000 && size >= 1 && size <= 200, "分页参数不合法");
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if (source != null && !source.isBlank()) {
      where.append(" and source=?");
      args.add(source);
    }
    if (run != null) {
      where.append(" and run_id=?");
      args.add(run);
    }
    if (outcome != null && !outcome.isBlank()) {
      where.append(" and outcome=?");
      args.add(outcome);
    }
    try {
      if (from != null && !from.isBlank()) {
        where.append(" and occurred_at>=?");
        args.add(OffsetDateTime.parse(from));
      }
      if (to != null && !to.isBlank()) {
        where.append(" and occurred_at<=?");
        args.add(OffsetDateTime.parse(to));
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("时间必须包含时区");
    }
    long total =
        db.queryForObject(
            "select count(*) from execution_logs" + where, Long.class, args.toArray());
    var queryArgs = new ArrayList<>(args);
    queryArgs.add(size);
    queryArgs.add((page - 1) * size);
    var rows =
        db.query(
            "select * from execution_logs"
                + where
                + " order by occurred_at desc,id desc limit ? offset ?",
            (r, n) -> {
              var m = new LinkedHashMap<String, Object>();
              m.put("id", r.getLong("id"));
              m.put("occurredAt", r.getObject("occurred_at", OffsetDateTime.class).toString());
              m.put("source", r.getString("source"));
              m.put("eventCode", r.getString("event_code"));
              m.put("outcome", r.getString("outcome"));
              m.put("actor", r.getString("actor_name"));
              m.put("runId", r.getObject("run_id"));
              m.put("stepKey", r.getString("step_key"));
              m.put("targetType", r.getString("target_type"));
              m.put("targetId", r.getObject("target_id"));
              m.put("message", r.getString("message"));
              try {
                m.put("details", json.readValue(r.getString("detail_json"), Map.class));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return m;
            },
            queryArgs.toArray());
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }

  /**
   * @description 查询选定日志所属步骤的全部摘要及原始事件元数据，大载荷按需读取
   * @author Freddy
   * @date 2026-11-13
   * @param id 列表条目主键
   * @return 条目、关联时间线和原始详情元数据
   */
  public Map<String, Object> detail(long id) {
    var entry = stored(id);
    String trace = (String) entry.get("RAW_TRACE_ID"), step = (String) entry.get("STEP_KEY");
    var args = new ArrayList<Object>();
    String where;
    if (entry.get("RUN_ID") != null) {
      where = "run_id=?";
      args.add(entry.get("RUN_ID"));
      if (step != null) {
        where += " and step_key=?";
        args.add(step);
      }
    } else if (trace != null) {
      where = "raw_trace_id=?";
      args.add(trace);
    } else {
      where = "id=?";
      args.add(id);
    }
    var events =
        db.query(
            "select id,occurred_at,event_code,outcome,message,step_key from execution_logs where "
                + where
                + " order by occurred_at,id",
            (r, n) -> {
              var event = new LinkedHashMap<String, Object>();
              event.put("id", r.getLong("id"));
              event.put("occurredAt", r.getObject("occurred_at", OffsetDateTime.class).toString());
              event.put("eventCode", r.getString("event_code"));
              event.put("outcome", r.getString("outcome"));
              event.put("message", r.getString("message"));
              event.put("stepKey", r.getString("step_key"));
              return event;
            },
            args.toArray());
    var original = new ArrayList<Map<String, Object>>();
    for (String related : traces(entry)) original.addAll(raw.metadata(related, step));
    original.sort(
        java.util.Comparator.comparing(
            event -> OffsetDateTime.parse(event.get("occurredAt").toString()).toInstant()));
    var visible = new LinkedHashMap<String, Object>();
    visible.put("id", id);
    visible.put("message", entry.get("MESSAGE"));
    visible.put("runId", entry.get("RUN_ID"));
    visible.put("stepKey", step);
    visible.put("source", entry.get("SOURCE"));
    return Map.of(
        "entry",
        visible,
        "events",
        events,
        "rawEvents",
        original,
        "hasOriginals",
        !original.isEmpty());
  }

  /**
   * @description 限定在当前日志所属执行与步骤内读取原始载荷
   * @author Freddy
   * @date 2026-11-13
   * @param id 摘要主键
   * @param rawId 原始事件主键
   * @return 解密后完整载荷
   */
  public Map<String, Object> original(long id, String rawId) {
    var entry = stored(id);
    for (String trace : traces(entry)) {
      try {
        return raw.payload(trace, (String) entry.get("STEP_KEY"), rawId);
      } catch (org.springframework.web.server.ResponseStatusException absent) {
        if (absent.getStatusCode().value() != 404) throw absent;
      }
    }
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND, "原始条目不存在或不属于当前步骤");
  }

  /**
   * @description 运行级条目汇总入队与执行上下文，步骤级仍严格限定步骤
   * @author Freddy
   * @date 2026-11-13
   * @param entry 选定摘要内部字段
   * @return 允许访问的执行关联集合
   */
  private List<String> traces(Map<String, Object> entry) {
    if (entry.get("RUN_ID") == null)
      return entry.get("RAW_TRACE_ID") == null
          ? List.of()
          : List.of(entry.get("RAW_TRACE_ID").toString());
    var args = new ArrayList<Object>();
    args.add(entry.get("RUN_ID"));
    String sql =
        "select distinct raw_trace_id from execution_logs where run_id=? and raw_trace_id is not null";
    if (entry.get("STEP_KEY") != null) {
      sql += " and step_key=?";
      args.add(entry.get("STEP_KEY"));
    }
    return db.queryForList(sql, String.class, args.toArray());
  }

  /**
   * @description 定位摘要记录，不向调用方暴露不存在记录的数据
   * @author Freddy
   * @date 2026-11-13
   * @param id 摘要主键
   * @return 内部数据库字段
   */
  private Map<String, Object> stored(long id) {
    var rows = db.queryForList("select * from execution_logs where id=?", id);
    if (rows.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "日志不存在");
    return rows.getFirst();
  }
}
