package com.certmanage.common;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * @description 通过现有管理员鉴权提供只读性能快照，不暴露目标地址、凭据或业务载荷
 * @author Freddy
 * @date 2026-11-16
 */
@RestController
@RequestMapping("/api/v1/system/performance")
public class PerformanceController {
  private final PerformanceMetrics metrics;
  private final HikariDataSource pool;
  private final JdbcTemplate db;
  private final ObjectProvider<PerformanceSource> sources;
  private volatile Map<String, Object> cachedQueues = Map.of();
  private volatile long queuesAt;

  /**
   * @description 注入聚合器与延迟解析的组件指标，不建立业务依赖环
   * @author Freddy
   * @date 2026-11-16
   * @param metrics API与池计数器
   * @param pool 连接池
   * @param db 只读队列查询
   * @param sources 可观测组件
   */
  public PerformanceController(
      PerformanceMetrics metrics,
      HikariDataSource pool,
      JdbcTemplate db,
      ObjectProvider<PerformanceSource> sources) {
    this.metrics = metrics;
    this.pool = pool;
    this.db = db;
    this.sources = sources;
  }

  /**
   * @description 返回累计耗时、瞬时池状态、缓存的数据库积压与组件快照，响应禁止缓存
   * @author Freddy
   * @date 2026-11-16
   * @return 管理员可读且不含秘密的快照
   */
  @GetMapping
  public ResponseEntity<?> snapshot() {
    var result = new LinkedHashMap<String, Object>();
    result.put("capturedAt", Instant.now().toString());
    result.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
    var memory = ManagementFactory.getMemoryMXBean();
    var heap = memory.getHeapMemoryUsage();
    var nonHeap = memory.getNonHeapMemoryUsage();
    var jvm = new LinkedHashMap<String, Object>();
    jvm.put("heapUsedBytes", heap.getUsed());
    jvm.put("heapCommittedBytes", heap.getCommitted());
    jvm.put("heapMaxBytes", heap.getMax());
    jvm.put("nonHeapUsedBytes", nonHeap.getUsed());
    jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
    jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    jvm.put(
        "gc",
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(
                gc ->
                    Map.of(
                        "name",
                        gc.getName(),
                        "collections",
                        gc.getCollectionCount(),
                        "timeMs",
                        gc.getCollectionTime()))
            .toList());
    result.put("jvm", jvm);
    var state = new LinkedHashMap<>(metrics.poolSnapshot());
    state.put("maxConnections", pool.getMaximumPoolSize());
    var bean = pool.getHikariPoolMXBean();
    if (bean != null) {
      state.put("active", bean.getActiveConnections());
      state.put("idle", bean.getIdleConnections());
      state.put("waiting", bean.getThreadsAwaitingConnection());
      state.put("total", bean.getTotalConnections());
    }
    result.put("databasePool", state);
    result.put("api", metrics.snapshot());
    result.put("queues", queues());
    var components = new TreeMap<String, Object>();
    sources
        .orderedStream()
        .forEach(
            source -> {
              try {
                components.put(source.performanceName(), source.performanceSnapshot());
              } catch (RuntimeException failure) {
                components.put(source.performanceName(), Map.of("status", "unavailable"));
              }
            });
    result.put("components", components);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }

  /**
   * @description 每五秒聚合一次活动队列，池已饱和时不再争抢连接
   * @author Freddy
   * @date 2026-11-16
   * @return 包含采样时间和可用状态的队列计数
   */
  private synchronized Map<String, Object> queues() {
    long now = System.nanoTime();
    if (!cachedQueues.isEmpty() && now - queuesAt < 5_000_000_000L) return cachedQueues;
    var bean = pool.getHikariPoolMXBean();
    if (bean != null && bean.getActiveConnections() >= pool.getMaximumPoolSize())
      return cachedQueues.isEmpty() ? Map.of("status", "pool-saturated") : cachedQueues;
    try {
      long queued = 0, running = 0, oldest = 0;
      for (var row :
          db.queryForList(
              "select status,count(*) amount,min(created_at) oldest from sync_runs where status in('QUEUED','RUNNING') group by status")) {
        long n = ((Number) row.get("AMOUNT")).longValue();
        if (row.get("STATUS").equals("QUEUED")) {
          queued = n;
          oldest =
              Math.max(
                  0,
                  Duration.between(((OffsetDateTime) row.get("OLDEST")).toInstant(), Instant.now())
                      .toMillis());
        } else running = n;
      }
      var notifications = new LinkedHashMap<String, Object>();
      for (String status : List.of("PENDING", "RETRY_WAIT", "SENDING", "FAILED"))
        notifications.put(status, 0L);
      for (var row :
          db.queryForList(
              "select delivery_status,count(*) amount from notification_deliveries where delivery_status in('PENDING','RETRY_WAIT','SENDING','FAILED') group by delivery_status"))
        notifications.put(
            row.get("DELIVERY_STATUS").toString(), ((Number) row.get("AMOUNT")).longValue());
      cachedQueues =
          Map.of(
              "status",
              "available",
              "capturedAt",
              Instant.now().toString(),
              "workflow",
              Map.of(
                  "queued",
                  queued,
                  "running",
                  running,
                  "oldestQueueAgeMs",
                  oldest,
                  "targetReservations",
                  db.queryForObject(
                      "select count(distinct s.server_id) from sync_run_servers s join sync_runs r on r.id=s.run_id where r.status='RUNNING'",
                      Integer.class)),
              "notifications",
              notifications);
      queuesAt = now;
      return cachedQueues;
    } catch (org.springframework.dao.DataAccessException failure) {
      return cachedQueues.isEmpty() ? Map.of("status", "unavailable") : cachedQueues;
    }
  }
}
