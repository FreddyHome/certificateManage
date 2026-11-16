package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.common.PerformanceMetrics;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * @description 验证指标维度有界、累计计数和分位上界语义
 * @author Freddy
 * @date 2026-11-16
 */
class PerformanceMetricsTest {
  /**
   * @description 路由种类异常增长时不保存无限直方图
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void capsRouteCardinalityAndPreservesCounts() {
    var metrics = new PerformanceMetrics();
    for (int i = 0; i < 2000; i++) metrics.record("GET", "/api/v1/test/route-" + i, 200, 2_000_000);
    @SuppressWarnings("unchecked")
    var routes = (List<Map<String, Object>>) metrics.snapshot().get("routes");
    assertTrue(routes.size() <= 128);
    assertEquals(
        2000L, routes.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum());
  }

  /**
   * @description 失败响应纳入计数，分位明确为桶上界
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void includesFailuresAndReportsHistogramBounds() {
    var metrics = new PerformanceMetrics();
    metrics.record("GET", "/api/v1/test/{id}", 200, 500_000);
    metrics.record("GET", "/api/v1/test/{id}", 422, 7_000_000);
    metrics.record("GET", "/api/v1/test/{id}", 500, 123_000_000);
    @SuppressWarnings("unchecked")
    var routes = (List<Map<String, Object>>) metrics.snapshot().get("routes");
    var row = routes.getFirst();
    assertEquals(3L, row.get("count"));
    assertEquals(1L, row.get("errors4xx"));
    assertEquals(1L, row.get("errors5xx"));
    assertEquals(250.0, ((Number) row.get("p95UpperBoundMs")).doubleValue());
  }
}
