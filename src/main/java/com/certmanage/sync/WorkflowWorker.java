package com.certmanage.sync;

import com.certmanage.common.ExecutionLog;
import com.certmanage.common.PerformanceSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @description 将有界执行线程与计划扫描和租约心跳分离，慢远端不会占用调度线程。
 * @author Freddy
 * @date 2026-11-16
 */
@Component
@ConditionalOnProperty(
    name = "app.sync.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WorkflowWorker implements PerformanceSource {
  private final ExecutionLog logs;
  private final WorkflowService service;
  private final WorkflowRunner runner;
  private ThreadPoolExecutor executor;

  @Value("${app.sync.parallelism:2}")
  private int parallelism = 2;

  @Value("${app.sync.shutdown-wait-seconds:5}")
  private int shutdownWaitSeconds = 5;

  /**
   * @description 装配调度入口，具体执行在独立线程中进行。
   * @author Freddy
   * @date 2026-11-16
   * @param service 任务服务
   * @param runner 执行入口
   * @param logs 执行日志
   */
  public WorkflowWorker(WorkflowService service, WorkflowRunner runner, ExecutionLog logs) {
    this.service = service;
    this.runner = runner;
    this.logs = logs;
  }

  /**
   * @description 创建无内存排队的固定线程池，数据库队列是唯一待执行队列。
   * @author Freddy
   * @date 2026-11-16
   */
  @PostConstruct
  public void start() {
    int count = Math.max(1, parallelism);
    executor =
        new ThreadPoolExecutor(
            count,
            count,
            0,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
              var thread = new Thread(r, "sync-execution");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * @description 启动后终止中断的旧执行，不自动重放远端操作。
   * @author Freddy
   * @date 2026-11-16
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    runner.recover();
  }

  /**
   * @description 仅向空闲执行线程派发查询，饱和时保留数据库中的排队任务。
   * @author Freddy
   * @date 2026-11-16
   */
  @Scheduled(fixedDelay = 1000, initialDelay = 10000)
  public void execute() {
    for (int i = 0; i < Math.max(1, parallelism); i++) {
      try {
        executor.execute(
            () -> {
              try {
                runner.runNext();
              } catch (Exception e) {
                failure("同步执行暂未完成，请检查数据库状态");
              }
            });
      } catch (RejectedExecutionException full) {
        break;
      }
    }
  }

  /**
   * @description 定时扫描业务执行计划，与远端线程相互独立。
   * @author Freddy
   * @date 2026-11-16
   */
  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void schedule() {
    try {
      service.schedule();
    } catch (Exception e) {
      failure("同步调度暂未完成，请检查任务配置");
    }
  }

  /**
   * @description 独立延长本进程任务租约。
   * @author Freddy
   * @date 2026-11-16
   */
  @Scheduled(fixedDelay = 30000, initialDelay = 10000)
  public void heartbeat() {
    runner.heartbeat();
  }

  /**
   * @description 记录后台失败，不将目标或命令写入指标。
   * @author Freddy
   * @date 2026-11-16
   * @param message 可公开的失败摘要
   */
  private void failure(String message) {
    logs.append(
        "SCHEDULER",
        "WORKER_FAILURE",
        "FAILURE",
        "system",
        null,
        null,
        null,
        null,
        "同步后台任务异常",
        Map.of());
    org.slf4j.LoggerFactory.getLogger(getClass()).warn(message);
  }

  /**
   * @description 有界等待执行完成，超时后中断线程并保留不自动重放语义。
   * @author Freddy
   * @date 2026-11-16
   */
  @PreDestroy
  public void stop() {
    if (executor == null) return;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(Math.max(0, shutdownWaitSeconds), TimeUnit.SECONDS))
        executor.shutdownNow();
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * @description 返回执行线程池指标名称。
   * @author Freddy
   * @date 2026-11-16
   * @return 指标名称
   */
  public String performanceName() {
    return "workflowExecutor";
  }

  /**
   * @description 返回有界线程池使用情况。
   * @author Freddy
   * @date 2026-11-16
   * @return 聚合线程池指标
   */
  public Map<String, Object> performanceSnapshot() {
    return Map.of(
        "active",
        executor.getActiveCount(),
        "poolSize",
        executor.getPoolSize(),
        "capacity",
        executor.getMaximumPoolSize(),
        "queueSize",
        executor.getQueue().size(),
        "shutdown",
        executor.isShutdown());
  }
}
