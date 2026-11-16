package com.certmanage.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.*;
import com.certmanage.server.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 使用阻塞远端桩验证跨任务并行与服务器互斥，不调用真实远端。
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:p2concurrency;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/test-master.key",
      "app.sync.worker-enabled=false"
    })
class WorkflowConcurrencyTest {
  @Autowired WorkflowService workflows;
  @Autowired WorkflowRunner runner;
  @Autowired ServerService servers;
  @Autowired JdbcTemplate db;
  @MockBean RemoteTransport transport;

  /**
   * @description 验证共享服务器任务排队时独立任务继续执行且心跳有效。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 隔离数据库或线程协作失败时传播
   */
  @Test
  void sharedServerWaitsWhileIndependentTaskRunsAndHeartbeatContinues() throws Exception {
    long server = server("shared"), independent = server("independent");
    long first = run(server, "first"),
        blocked = run(server, "blocked"),
        other = run(independent, "other");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(transport.execute(anyLong(), anyLong(), anyMap(), isNull(), isNull()))
        .thenAnswer(
            call -> {
              if (call.<Long>getArgument(0) == server) {
                entered.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
              }
              return Map.of("message", "ok");
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var running = pool.submit(() -> runner.executeRun(first));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var next = pool.submit(() -> runner.runNext());
      next.get(5, TimeUnit.SECONDS);
      assertThat(workflows.runDetail(blocked).get("status")).isEqualTo("QUEUED");
      assertThat(workflows.runDetail(other).get("status")).isEqualTo("SUCCESS");
      runner.heartbeat();
      assertThat(
              db.queryForObject(
                  "select lease_until > current_timestamp from sync_runs where id=?",
                  Boolean.class,
                  first))
          .isTrue();
      release.countDown();
      running.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    runner.runNext();
    assertThat(workflows.runDetail(blocked).get("status")).isEqualTo("SUCCESS");
  }

  /**
   * @description 即使存在旧版本或直接入库的同任务排队记录，也不能同时领取。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 线程协作或隔离数据库操作失败时传播
   */
  @Test
  void sameTaskCannotBeClaimedTwice() throws Exception {
    long server = server("same-task");
    long first = run(server, "same-task");
    db.update(
        "insert into sync_runs(task_id,trigger_type,task_revision,status,snapshot_json) select task_id,trigger_type,task_revision,'QUEUED',snapshot_json from sync_runs where id=?",
        first);
    long second = db.queryForObject("select max(id) from sync_runs", Long.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(transport.execute(anyLong(), anyLong(), anyMap(), isNull(), isNull()))
        .thenAnswer(
            call -> {
              entered.countDown();
              release.await(10, TimeUnit.SECONDS);
              return Map.of();
            });
    try (var pool = Executors.newSingleThreadExecutor()) {
      var executing = pool.submit(() -> runner.executeRun(first));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      runner.executeRun(second);
      assertThat(workflows.runDetail(second).get("status")).isEqualTo("QUEUED");
      release.countDown();
      executing.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    runner.executeRun(second);
    assertThat(workflows.runDetail(second).get("status")).isEqualTo("SUCCESS");
  }

  /**
   * @description 创建仅供远端桩使用的服务器配置。
   * @author Freddy
   * @date 2026-11-16
   * @param name 测试别名
   * @return 服务器主键
   */
  private long server(String name) {
    return ((Number)
            servers
                .save(
                    null,
                    Map.of(
                        "alias",
                        name,
                        "type",
                        "SSH",
                        "config",
                        Map.of("host", "example.invalid", "username", "test", "password", "test")))
                .get("id"))
        .longValue();
  }

  /**
   * @description 保存可执行任务并冻结排队快照。
   * @author Freddy
   * @date 2026-11-16
   * @param server 目标服务器
   * @param name 测试任务名称
   * @return 排队执行主键
   */
  private long run(long server, String name) {
    var task =
        workflows.save(
            null,
            Map.of(
                "name",
                name,
                "state",
                "READY",
                "steps",
                List.of(
                    Map.of(
                        "key",
                        "shell",
                        "name",
                        "shell",
                        "type",
                        "SSH",
                        "config",
                        Map.of("serverId", server, "command", "echo test")))),
            "admin");
    long id = ((Number) task.get("id")).longValue();
    workflows.validate(id);
    return workflows.enqueue(id, UUID.randomUUID().toString(), "admin");
  }
}
