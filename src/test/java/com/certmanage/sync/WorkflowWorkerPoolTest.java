package com.certmanage.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.ExecutionLog;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * @description 验证工作流执行线程池容量、扫描快速返回及关闭释放。
 * @author Freddy
 * @date 2026-11-16
 */
class WorkflowWorkerPoolTest {
  /**
   * @description 验证工作线程有界、心跳独立和关闭状态。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 隔离数据库或线程协作失败时传播
   */
  @Test
  void dispatchIsBoundedAndCloseStopsWorkers() throws Exception {
    var runner = mock(WorkflowRunner.class);
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(runner)
        .runNext();
    var worker = new WorkflowWorker(mock(WorkflowService.class), runner, mock(ExecutionLog.class));
    worker.start();
    try {
      worker.execute();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 10; i++) worker.execute();
      assertThat(worker.performanceSnapshot())
          .containsEntry("active", 2)
          .containsEntry("queueSize", 0);
      worker.heartbeat();
      verify(runner).heartbeat();
      release.countDown();
    } finally {
      release.countDown();
      worker.stop();
    }
    assertThat(worker.performanceSnapshot()).containsEntry("shutdown", true);
  }
}
