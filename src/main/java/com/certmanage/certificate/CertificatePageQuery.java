package com.certmanage.certificate;

import static com.certmanage.common.Errors.require;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 以固定次数SQL查询证书分页摘要，集中维护筛选与有效期边界
 * @author Freddy
 * @date 2026-11-16
 */
final class CertificatePageQuery {
  private static final String FROM =
      " from certificate_assets a join certificate_materials m on m.id=a.material_id left join customers c on c.id=a.customer_id ";
  private static final String FIELDS =
      "select a.id,a.owner_type,a.enabled,a.archived_at,a.customer_id,a.purpose,a.certificate_kind,a.chain_depth,m.common_name,m.serial_hex,m.not_before,m.not_after,c.customer_name,im.common_name issuer_name,exists(select 1 from private_keys k where k.own_asset_id=a.id) has_key";
  private final JdbcTemplate db;
  private final Clock clock;

  /**
   * @description 默认使用UTC时钟计算列表状态和筛选窗口
   * @author Freddy
   * @date 2026-11-16
   * @param db 数据库模板
   */
  CertificatePageQuery(JdbcTemplate db) {
    this(db, Clock.systemUTC());
  }

  /**
   * @description 注入统一时钟，允许验证生效、到期及预警的精确边界
   * @author Freddy
   * @date 2026-11-16
   * @param db 数据库模板
   * @param clock 本次查询所用时钟
   */
  CertificatePageQuery(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /**
   * @description 在数据库完成筛选和分页，不在结果映射期间发起额外查询
   * @author Freddy
   * @date 2026-11-16
   * @param owner 可选归属，普通查询默认OWN
   * @param kind 可选层级
   * @param query CN和客户的字面包含查询，最长200字
   * @param purpose 可选用途
   * @param status 可选有效期状态
   * @param page 页码，1至1000000
   * @param size 页大小，1至100
   * @param archived 是否仅查询归档资产
   * @return 分页条目、总数和页参数
   * @throws IllegalArgumentException 参数不合法
   */
  Map<String, Object> page(
      String owner,
      String kind,
      String query,
      String purpose,
      String status,
      int page,
      int size,
      boolean archived) {
    if (owner == null && !archived) owner = "OWN";
    require(owner == null || Set.of("OWN", "CUSTOMER").contains(owner), "归属错误");
    require(
        kind == null || kind.isEmpty() || Set.of("ROOT", "INTERMEDIATE", "LEAF").contains(kind),
        "证书层级错误");
    require(
        purpose == null || purpose.isEmpty() || Set.of("AS2", "HTTPS", "GENERAL").contains(purpose),
        "证书用途错误");
    require(
        status == null || status.isEmpty() || Set.of("未生效", "已过期", "即将过期", "有效").contains(status),
        "证书状态错误");
    require(page >= 1 && page <= 1000000 && size >= 1 && size <= 100, "分页参数超出范围");
    require(query == null || query.length() <= 200, "搜索内容不得超过200字");
    Instant now = clock.instant();
    int warning =
        db.queryForObject("select warning_days from notification_policy where id=1", Integer.class);
    var where =
        new StringBuilder(
            " where a.deleted_at is null and a.archived_at is " + (archived ? "not null" : "null"));
    var args = new ArrayList<Object>();
    if (owner != null) {
      where.append(" and a.owner_type=?");
      args.add(owner);
    }
    if (kind != null && !kind.isEmpty()) {
      where.append(" and a.certificate_kind=?");
      args.add(kind);
    }
    if (purpose != null && !purpose.isEmpty()) {
      where.append(" and a.purpose=?");
      args.add(purpose);
    }
    if (query != null && !query.isEmpty()) {
      where.append(
          " and lower(m.common_name || ' ' || coalesce(c.customer_name,'')) like ? escape '!'");
      args.add(
          "%"
              + query
                  .toLowerCase(Locale.ROOT)
                  .replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_")
              + "%");
    }
    if (status != null && !status.isEmpty()) {
      var time = now.atOffset(ZoneOffset.UTC);
      if (status.equals("未生效")) {
        where.append(" and m.not_before>?");
        args.add(time);
      } else if (status.equals("已过期")) {
        where.append(" and m.not_before<=? and m.not_after<=?");
        args.add(time);
        args.add(time);
      } else {
        where.append(
            " and m.not_before<=? and m.not_after>? and m.not_after"
                + (status.equals("即将过期") ? "<?" : ">=?"));
        args.add(time);
        args.add(time);
        args.add(now.plusSeconds(warning * 86400L).atOffset(ZoneOffset.UTC));
      }
    }
    long total = db.queryForObject("select count(*)" + FROM + where, Long.class, args.toArray());
    var pagedArgs = new ArrayList<>(args);
    pagedArgs.add(size);
    pagedArgs.add((page - 1L) * size);
    var rows =
        db.query(
            FIELDS
                + FROM
                + " left join certificate_materials im on im.id=a.issuer_material_id "
                + where
                + " order by m.not_after,a.id limit ? offset ?",
            (r, n) -> row(r, now, warning),
            pagedArgs.toArray());
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }

  /**
   * @description 只映射表格字段，Subject、SAN及证书材料由详情接口按需读取
   * @author Freddy
   * @date 2026-11-16
   * @param r 当前结果行
   * @param now 本次请求时间
   * @param warning 预警天数
   * @return 与现有表格兼容的摘要字段
   * @throws SQLException 数据库字段读取失败
   */
  private Map<String, Object> row(ResultSet r, Instant now, int warning) throws SQLException {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", r.getLong("id"));
    result.put("name", r.getString("common_name"));
    result.put("owner", r.getString("owner_type"));
    result.put("enabled", r.getBoolean("enabled"));
    var archived = r.getObject("archived_at", OffsetDateTime.class);
    result.put("archivedAt", archived == null ? null : archived.toInstant().toString());
    result.put("customer", r.getString("customer_name"));
    result.put("customerId", r.getObject("customer_id", Long.class));
    result.put("purpose", r.getString("purpose"));
    result.put("kind", r.getString("certificate_kind"));
    result.put("depth", r.getObject("chain_depth"));
    result.put("issuer", r.getString("issuer_name"));
    Instant start = r.getObject("not_before", OffsetDateTime.class).toInstant();
    Instant end = r.getObject("not_after", OffsetDateTime.class).toInstant();
    result.put("notBefore", start.toString());
    result.put("notAfter", end.toString());
    result.put("status", validityStatus(start, end, now, warning));
    result.put("hasKey", r.getBoolean("has_key"));
    result.put("serial", r.getString("serial_hex"));
    return result;
  }

  /**
   * @description 对齐筛选与展示边界，截止时刻为过期，恰在预警阈值时仍为有效
   * @author Freddy
   * @date 2026-11-16
   * @param start 生效时刻
   * @param end 截止时刻
   * @param now 当前时刻
   * @param warning 预警天数
   * @return 四种中文有效期状态之一
   */
  static String validityStatus(Instant start, Instant end, Instant now, int warning) {
    return now.isBefore(start)
        ? "未生效"
        : !now.isBefore(end)
            ? "已过期"
            : now.plusSeconds(warning * 86400L).isAfter(end) ? "即将过期" : "有效";
  }
}
