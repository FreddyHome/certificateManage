package com.certmanage.common;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * @description 在Hikari启动前接入等待和超时回调，不更改连接池容量或超时
 * @author Freddy
 * @date 2026-11-16
 */
@Component
public class PoolPerformanceInstrumentation implements BeanPostProcessor {
  private final org.springframework.beans.factory.ObjectProvider<PerformanceMetrics> metrics;

  /**
   * @description 注入计数器
   * @author Freddy
   * @date 2026-11-16
   * @param metrics 性能计数器
   */
  public PoolPerformanceInstrumentation(
      org.springframework.beans.factory.ObjectProvider<PerformanceMetrics> metrics) {
    this.metrics = metrics;
  }

  /**
   * @description 仅为尚未安装其他追踪器的数据源设置Hikari回调
   * @author Freddy
   * @date 2026-11-16
   * @param bean 已初始化bean
   * @param name bean名称
   * @return 原bean
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String name) {
    if (bean instanceof HikariDataSource source && source.getMetricsTrackerFactory() == null) {
      source.setMetricsTrackerFactory((pool, stats) -> new Tracker(metrics.getObject()));
    }
    return bean;
  }

  /**
   * @description 无外部依赖的池指标适配器
   * @author Freddy
   * @date 2026-11-16
   */
  private record Tracker(PerformanceMetrics metrics) implements IMetricsTracker {
    /**
     * @description 转发连接获取耗时
     * @author Freddy
     * @date 2026-11-16
     * @param nanos 等待时间
     */
    @Override
    public void recordConnectionAcquiredNanos(long nanos) {
      metrics.connectionAcquired(nanos);
    }

    /**
     * @description 转发连接获取超时
     * @author Freddy
     * @date 2026-11-16
     */
    @Override
    public void recordConnectionTimeout() {
      metrics.connectionTimedOut();
    }
  }
}
