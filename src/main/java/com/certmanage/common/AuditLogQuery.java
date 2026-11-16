package com.certmanage.common;

import static com.certmanage.common.Errors.require;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 以明确字段投影提供兼容偏移分页与可选计数的审计游标检索
 * @author Freddy
 * @date 2026-11-16
 */
final class AuditLogQuery {
  private static final String COLUMNS =
      "id,occurred_at,source,event_code,outcome,actor_name,run_id,step_key,target_type,target_id,message,detail_json";
  private final JdbcTemplate db;
  private final ObjectMapper json;

  /**
   * @description 装配只读查询依赖
   * @author Freddy
   * @date 2026-11-16
   * @param db 数据库模板
   * @param json JSON编码器
   */
  AuditLogQuery(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  /**
   * @description 对固定筛选条件做稳定分页，游标仅决定位置，不授予访问权限
   * @author Freddy
   * @date 2026-11-16
   * @param source 来源条件
   * @param run 执行ID条件
   * @param outcome 结果条件
   * @param from 起始时间，含时区
   * @param to 结束时间，含时区
   * @param page 兼容页码
   * @param size 每页1至200条
   * @param cursorMode 是否使用游标，首页游标可空
   * @param cursor 前页返回的游标
   * @param includeTotal 是否查询全部匹配总数
   * @return 当前页、总数或游标推进信息
   * @throws IllegalArgumentException 参数或游标条件不一致
   */
  Map<String, Object> list(
      String source,
      Long run,
      String outcome,
      String from,
      String to,
      int page,
      int size,
      boolean cursorMode,
      String cursor,
      boolean includeTotal) {
    require(page >= 1 && page <= 1000000 && size >= 1 && size <= 200, "分页参数不合法");
    source = normalized(source);
    outcome = normalized(outcome);
    require(source == null || source.length() <= 32, "日志来源过长");
    require(outcome == null || outcome.length() <= 16, "日志结果过长");
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if (source != null) {
      where.append(" and source=?");
      args.add(source);
    }
    if (run != null) {
      where.append(" and run_id=?");
      args.add(run);
    }
    if (outcome != null) {
      where.append(" and outcome=?");
      args.add(outcome);
    }
    OffsetDateTime start = parseTime(from), end = parseTime(to);
    if (start != null) {
      where.append(" and occurred_at>=?");
      args.add(start);
    }
    if (end != null) {
      where.append(" and occurred_at<=?");
      args.add(end);
    }
    var result = new LinkedHashMap<String, Object>();
    if (includeTotal)
      result.put(
          "total",
          db.queryForObject(
              "select count(*) from execution_logs" + where, Long.class, args.toArray()));
    String scope = scope(source, run, outcome, start, end, size);
    if (cursorMode && cursor != null && !cursor.isBlank()) {
      var position = position(cursor, scope);
      where.append(" and (occurred_at,id)<(?,?)");
      args.add(position.time());
      args.add(position.id());
    }
    var values = new ArrayList<>(args);
    values.add(cursorMode ? size + 1 : size);
    if (!cursorMode) values.add((page - 1L) * size);
    var rows =
        db.query(
            "select "
                + COLUMNS
                + " from execution_logs"
                + where
                + " order by occurred_at desc,id desc limit ?"
                + (cursorMode ? "" : " offset ?"),
            (r, n) -> {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", r.getLong("id"));
              row.put("occurredAt", r.getObject("occurred_at", OffsetDateTime.class).toString());
              row.put("source", r.getString("source"));
              row.put("eventCode", r.getString("event_code"));
              row.put("outcome", r.getString("outcome"));
              row.put("actor", r.getString("actor_name"));
              row.put("runId", r.getObject("run_id"));
              row.put("stepKey", r.getString("step_key"));
              row.put("targetType", r.getString("target_type"));
              row.put("targetId", r.getObject("target_id"));
              row.put("message", r.getString("message"));
              try {
                row.put("details", json.readValue(r.getString("detail_json"), Map.class));
              } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("审计日志解码失败", e);
              }
              return row;
            },
            values.toArray());
    boolean more = cursorMode && rows.size() > size;
    var items = more ? new ArrayList<>(rows.subList(0, size)) : rows;
    result.put("items", items);
    result.put("size", size);
    if (cursorMode) {
      result.put("hasMore", more);
      result.put("nextCursor", more ? cursor(items.getLast(), scope) : null);
    } else result.put("page", page);
    return result;
  }

  /**
   * @description 统一空筛选值而不改变非空枚举值
   * @author Freddy
   * @date 2026-11-16
   * @param value 原始条件
   * @return 空条件为null
   */
  private String normalized(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * @description 校验时区时间长度并解析，避免接受无时区日期
   * @author Freddy
   * @date 2026-11-16
   * @param value 原始时间
   * @return 可空时间
   * @throws IllegalArgumentException 时间不合法
   */
  private OffsetDateTime parseTime(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      require(value.length() <= 64, "时间必须包含时区");
      return OffsetDateTime.parse(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("时间必须包含时区");
    }
  }

  /**
   * @description 将游标绑定到同一筛选和页大小，避免筛选变化后误用旧位置
   * @author Freddy
   * @date 2026-11-16
   * @param source 来源
   * @param run 执行ID
   * @param outcome 结果
   * @param from 起始时间
   * @param to 结束时间
   * @param size 页大小
   * @return 非秘密条件摘要，不是身份凭据
   */
  private String scope(
      String source, Long run, String outcome, OffsetDateTime from, OffsetDateTime to, int size) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      json.writeValueAsBytes(
                          Arrays.asList(
                              source,
                              run,
                              outcome,
                              from == null ? null : from.toInstant().toString(),
                              to == null ? null : to.toInstant().toString(),
                              size))));
    } catch (Exception e) {
      throw new IllegalStateException("游标条件编码失败", e);
    }
  }

  /**
   * @description 校验游标结构、位置和条件摘要
   * @author Freddy
   * @date 2026-11-16
   * @param token 前页游标
   * @param scope 当前条件摘要
   * @return 下一页边界
   * @throws IllegalArgumentException 游标无效或条件已变化
   */
  private Position position(String token, String scope) {
    try {
      require(token.length() <= 256, "游标不合法");
      String[] parts =
          new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
      require(parts.length == 3 && scope.equals(parts[2]), "游标与查询条件不一致，请重新查询");
      long id = Long.parseLong(parts[1]);
      require(id > 0, "游标不合法");
      return new Position(OffsetDateTime.parse(parts[0]), id);
    } catch (Exception e) {
      throw new IllegalArgumentException("游标不合法或查询条件已变化，请重新查询");
    }
  }

  /**
   * @description 编码时间与ID稳定边界
   * @author Freddy
   * @date 2026-11-16
   * @param row 当前页最后一条
   * @param scope 条件摘要
   * @return URL安全的下一页游标
   */
  private String cursor(Map<String, Object> row, String scope) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (row.get("occurredAt") + "|" + row.get("id") + "|" + scope)
                .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @description 保存时间与主键组成的稳定位置
   * @author Freddy
   * @date 2026-11-16
   * @param time 事件时间
   * @param id 事件ID
   */
  private record Position(OffsetDateTime time, long id) {}
}
