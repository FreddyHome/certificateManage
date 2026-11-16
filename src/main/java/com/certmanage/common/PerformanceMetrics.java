package com.certmanage.common;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;

/**
 * @description 按固定路由模板统计累计耗时分布，限制维度数量且不采集请求参数
 * @author Freddy
 * @date 2026-11-16
 */
@Component
public class PerformanceMetrics {
  private static final long[] LIMITS = {
    1, 5, 10, 25, 50, 100, 250, 500, 1000, 3000, 10000, 30000, Long.MAX_VALUE
  };
  private static final int MAX_ROUTES = 128;
  private final ConcurrentHashMap<String, Timing> routes = new ConcurrentHashMap<>();
  private final LongAdder acquisitions = new LongAdder(),
      acquisitionNanos = new LongAdder(),
      timeouts = new LongAdder();
  private final AtomicLong maxAcquisitionNanos = new AtomicLong();

  /**
   * @description 记录一个已完成API，超额维度合并而非增加堆对象
   * @author Freddy
   * @date 2026-11-16
   * @param method HTTP方法
   * @param route 服务端匹配路由模板，不含查询参数
   * @param status 响应状态
   * @param nanos 总耗时纳秒
   */
  public void record(String method, String route, int status, long nanos) {
    method =
        Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)
            ? method
            : "OTHER";
    if (route == null || !route.startsWith("/api/v1/") || route.length() > 200) route = "UNMAPPED";
    String key = method + " " + route;
    Timing timing = routes.get(key);
    if (timing == null)
      synchronized (routes) {
        timing = routes.get(key);
        if (timing == null) {
          if (routes.size() >= MAX_ROUTES - 1) key = "OTHER OVERFLOW";
          timing = routes.computeIfAbsent(key, k -> new Timing());
        }
      }
    timing.add(status, nanos);
  }

  /**
   * @description 记录连接池真实获取等待，包含成功获取次数
   * @author Freddy
   * @date 2026-11-16
   * @param nanos 获取耗时
   */
  public void connectionAcquired(long nanos) {
    acquisitions.increment();
    acquisitionNanos.add(nanos);
    maxAcquisitionNanos.accumulateAndGet(nanos, Math::max);
  }

  /**
   * @description 记录Hikari报告的连接获取超时
   * @author Freddy
   * @date 2026-11-16
   */
  public void connectionTimedOut() {
    timeouts.increment();
  }

  /**
   * @description 返回进程生命周期内的连接等待聚合
   * @author Freddy
   * @date 2026-11-16
   * @return 不含数据库URL的计数
   */
  public Map<String, Object> poolSnapshot() {
    return Map.of(
        "acquisitionCount",
        acquisitions.sum(),
        "acquisitionTotalMs",
        acquisitionNanos.sum() / 1_000_000.0,
        "acquisitionMaxMs",
        maxAcquisitionNanos.get() / 1_000_000.0,
        "timeoutCount",
        timeouts.sum());
  }

  /**
   * @description 获取有界路由维度的累计直方图，分位值是桶上界而非精确采样分位
   * @author Freddy
   * @date 2026-11-16
   * @return 累计API指标
   */
  public Map<String, Object> snapshot() {
    var rows = new ArrayList<Map<String, Object>>();
    routes.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var row = new LinkedHashMap<>(entry.getValue().snapshot());
              String[] key = entry.getKey().split(" ", 2);
              row.put("method", key[0]);
              row.put("route", key[1]);
              rows.add(row);
            });
    return Map.of(
        "aggregation",
        "process-cumulative",
        "percentiles",
        "histogram-upper-bound-ms",
        "routeLimit",
        MAX_ROUTES,
        "routes",
        rows);
  }

  /**
   * @description 使用固定数量计数器维护单路由耗时分布
   * @author Freddy
   * @date 2026-11-16
   */
  private static final class Timing {
    private final LongAdder count = new LongAdder(),
        total = new LongAdder(),
        clientErrors = new LongAdder(),
        serverErrors = new LongAdder();
    private final AtomicLong max = new AtomicLong();
    private final LongAdder[] buckets =
        Arrays.stream(LIMITS).mapToObj(v -> new LongAdder()).toArray(LongAdder[]::new);

    /**
     * @description 累加成功和失败响应，不保存单个请求记录
     * @author Freddy
     * @date 2026-11-16
     * @param status HTTP状态码
     * @param nanos 耗时
     */
    void add(int status, long nanos) {
      nanos = Math.max(0, nanos);
      count.increment();
      total.add(nanos);
      max.accumulateAndGet(nanos, Math::max);
      if (status >= 500) serverErrors.increment();
      else if (status >= 400) clientErrors.increment();
      double millis = nanos / 1_000_000.0;
      for (int i = 0; i < LIMITS.length; i++)
        if (millis <= LIMITS[i]) {
          buckets[i].increment();
          break;
        }
    }

    /**
     * @description 返回弱一致但线程安全的累计快照
     * @author Freddy
     * @date 2026-11-16
     * @return 计数与毫秒耗时
     */
    Map<String, Object> snapshot() {
      long n = count.sum();
      var histogram = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < LIMITS.length; i++)
        histogram.add(
            Map.of(
                "upperBoundMs",
                LIMITS[i] == Long.MAX_VALUE ? "unbounded" : LIMITS[i],
                "count",
                buckets[i].sum()));
      return Map.of(
          "count",
          n,
          "totalMs",
          total.sum() / 1_000_000.0,
          "maxMs",
          max.get() / 1_000_000.0,
          "errors4xx",
          clientErrors.sum(),
          "errors5xx",
          serverErrors.sum(),
          "p50UpperBoundMs",
          percentile(n, .5),
          "p95UpperBoundMs",
          percentile(n, .95),
          "histogram",
          histogram);
    }

    /**
     * @description 按累计桶估算分位上界，小样本不做容量承诺
     * @author Freddy
     * @date 2026-11-16
     * @param n 样本数
     * @param quantile 分位比例
     * @return 毫秒桶上界
     */
    double percentile(long n, double quantile) {
      if (n == 0) return 0;
      long target = (long) Math.ceil(n * quantile), sum = 0;
      for (int i = 0; i < LIMITS.length; i++) {
        sum += buckets[i].sum();
        if (sum >= target) return LIMITS[i] == Long.MAX_VALUE ? max.get() / 1_000_000.0 : LIMITS[i];
      }
      return max.get() / 1_000_000.0;
    }
  }
}
