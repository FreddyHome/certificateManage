package com.certmanage.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * @description 原样加密保存执行详情，使用可嵌套上下文关联步骤，写库重试不重放远端操作
 * @author Freddy
 * @date 2026-11-13
 */
@Service
public class RawExecutionLog {
  private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> false);
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ObjectMapper rawJson;
  private final org.springframework.transaction.support.TransactionTemplate writeTransaction;
  private final SecretStore secrets;
  private final Queue<Pending> pending = new ConcurrentLinkedQueue<>();
  private final java.util.concurrent.ExecutorService writer =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            var thread = new Thread(r, "raw-audit-writer");
            thread.setDaemon(true);
            return thread;
          });
  private final java.util.concurrent.atomic.AtomicBoolean scheduled =
      new java.util.concurrent.atomic.AtomicBoolean();

  private record Pending(
      String id,
      String trace,
      String step,
      long order,
      OffsetDateTime time,
      String kind,
      String title,
      int size,
      SecretStore.Sealed sealed) {}

  private static final class State {
    final RawExecutionLog owner;
    final String trace = UUID.randomUUID().toString();
    final AtomicLong sequence = new AtomicLong();
    String step;

    State(RawExecutionLog owner, String step) {
      this.owner = owner;
      this.step = step;
    }
  }

  /**
   * @description 注入数据库、JSON编码器和现有主密钥加密服务
   * @author Freddy
   * @date 2026-11-13
   * @param db 日志数据库
   * @param json JSON编码器
   * @param secrets 主密钥加密服务
   * @param transactions 数据库事务管理器
   */
  public RawExecutionLog(
      JdbcTemplate db,
      ObjectMapper json,
      SecretStore secrets,
      org.springframework.transaction.PlatformTransactionManager transactions) {
    this.db = db;
    this.json = json;
    this.secrets = secrets;
    this.rawJson = json.copy();
    this.rawJson
        .getFactory()
        .setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(32 * 1024 * 1024)
                .build());
    this.writeTransaction =
        new org.springframework.transaction.support.TransactionTemplate(transactions);
    this.writeTransaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * @description 建立独立执行上下文，结束后恢复调用方上下文
   * @author Freddy
   * @date 2026-11-13
   * @return 可自动关闭的上下文
   */
  public Scope begin() {
    var previous = CURRENT.get();
    var state = new State(this, null);
    CURRENT.set(state);
    return new Scope(previous, state);
  }

  /**
   * @description 防止上下文跨请求或步骤执行泄漏
   * @author Freddy
   * @date 2026-11-13
   */
  public static final class Scope implements AutoCloseable {
    private final State previous, state;
    private boolean closed;

    private Scope(State previous, State state) {
      this.previous = previous;
      this.state = state;
    }

    /**
     * @description 恢复进入前的执行上下文
     * @author Freddy
     * @date 2026-11-13
     */
    @Override
    public void close() {
      if (!closed) {
        if (CURRENT.get() == state) {
          if (previous == null) CURRENT.remove();
          else CURRENT.set(previous);
        }
        closed = true;
      }
    }
  }

  /**
   * @description 切换当前同步步骤，后续事件只关联此步骤
   * @author Freddy
   * @date 2026-11-13
   * @param key 步骤标识，运行级事件可传空
   */
  public static void step(String key) {
    var state = CURRENT.get();
    if (state != null) state.step = key;
  }

  /**
   * @description 判断日志适配器是否属于当前执行的服务实例
   * @author Freddy
   * @date 2026-11-13
   * @return 当前上下文由本实例拥有
   */
  public boolean isCurrent() {
    var state = CURRENT.get();
    return state != null && state.owner == this && !WRITING.get();
  }

  /**
   * @description 捕获可跨线程使用的固定步骤事件接收器，避免异步回调串到下一步骤
   * @author Freddy
   * @date 2026-11-13
   * @return 原样事件接收器，无上下文时为空接收器
   */
  public static Sink sink() {
    var state = CURRENT.get();
    return state == null ? new Sink(null, null) : new Sink(state, state.step);
  }

  /**
   * @description 保存异步连接事件所属的固定步骤
   * @author Freddy
   * @date 2026-11-13
   */
  public static final class Sink {
    private final State state;
    private final String step;

    private Sink(State state, String step) {
      this.state = state;
      this.step = step;
    }

    /**
     * @description 保存原始事件，数据在调用返回前编码和加密
     * @author Freddy
     * @date 2026-11-13
     * @param kind 事件类型
     * @param title 简短标题
     * @param payload 原始载荷
     */
    public void record(String kind, String title, Map<String, ?> payload) {
      if (state != null)
        state.owner.store(
            state.trace, step, state.sequence.incrementAndGet(), kind, title, payload, true);
    }

    /**
     * @description 将异步会话异常关联到发起连接的步骤
     * @author Freddy
     * @date 2026-11-13
     * @param failure 原始会话异常
     */
    public void exception(Throwable failure) {
      if (failure == null) return;
      var out = new StringWriter();
      failure.printStackTrace(new PrintWriter(out));
      record(
          "EXCEPTION",
          "原始会话异常",
          Map.of(
              "exceptionType",
              failure.getClass().getName(),
              "message",
              Objects.toString(failure.getMessage(), ""),
              "stackTrace",
              out.toString()));
    }
  }

  /**
   * @description 将当前执行事件原样送入加密详情
   * @author Freddy
   * @date 2026-11-13
   * @param kind 类型
   * @param title 标题
   * @param payload 原始字段
   */
  public static void event(String kind, String title, Map<String, ?> payload) {
    if (!WRITING.get()) sink().record(kind, title, payload);
  }

  /**
   * @description 保存包含cause与suppressed异常的完整原始堆栈
   * @author Freddy
   * @date 2026-11-13
   * @param failure 原始异常
   */
  public static void exception(Throwable failure) {
    if (failure == null) return;
    var out = new StringWriter();
    failure.printStackTrace(new PrintWriter(out));
    var data = new LinkedHashMap<String, Object>();
    data.put("exceptionType", failure.getClass().getName());
    data.put("message", failure.getMessage());
    data.put("stackTrace", out.toString());
    event("EXCEPTION", "原始异常", data);
  }

  /**
   * @description 保存过滤前的应用审计事件，并返回摘要记录使用的关联标识
   * @author Freddy
   * @date 2026-11-13
   * @param code 原事件代码
   * @param message 原事件消息
   * @param details 原始字段
   * @param step 所属步骤
   * @return 原始详情关联标识
   */
  public String application(String code, String message, Map<String, ?> details, String step) {
    var state = CURRENT.get();
    String trace = state == null ? UUID.randomUUID().toString() : state.trace;
    var payload = new LinkedHashMap<String, Object>();
    payload.put("eventCode", code);
    payload.put("message", message);
    payload.put("details", details);
    store(
        trace,
        step,
        state == null ? 1 : state.sequence.incrementAndGet(),
        "APPLICATION",
        "应用执行事件",
        payload,
        false);
    return trace;
  }

  /**
   * @description 先编码加密，再保存或排队重试，原始明文不进入普通日志
   * @author Freddy
   * @date 2026-11-13
   * @param trace 执行关联标识
   * @param step 步骤标识
   * @param order 执行顺序
   * @param kind 类型
   * @param title 标题
   * @param payload 原始数据
   * @param asynchronous 是否禁止在调用线程等待数据库连接
   */
  private void store(
      String trace,
      String step,
      long order,
      String kind,
      String title,
      Map<String, ?> payload,
      boolean asynchronous) {
    if (WRITING.get()) return;
    WRITING.set(true);
    try {
      byte[] bytes = json.writeValueAsBytes(payload);
      var event =
          new Pending(
              UUID.randomUUID().toString(),
              trace,
              step,
              order,
              OffsetDateTime.now(),
              kind,
              title,
              bytes.length,
              secrets.seal(bytes));
      Arrays.fill(bytes, (byte) 0);
      if (asynchronous
          || org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()) {
        pending.add(event);
        scheduleFlush();
      } else
        try {
          insert(event);
        } catch (DataAccessException
            | org.springframework.transaction.TransactionException unavailable) {
          pending.add(event);
        }
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("原始日志编码失败", failure);
    } finally {
      WRITING.remove();
    }
  }

  /**
   * @description 仅追加不可变密文，重试遇到已保存ID时视为成功
   * @author Freddy
   * @date 2026-11-13
   * @param event 已加密事件
   */
  private void insert(Pending event) {
    try {
      writeTransaction.executeWithoutResult(
          status ->
              db.update(
                  "insert into execution_log_raw(id,trace_id,step_key,event_order,occurred_at,kind,title,payload_bytes,payload_ciphertext,payload_nonce) values(?,?,?,?,?,?,?,?,?,?)",
                  event.id,
                  event.trace,
                  event.step,
                  event.order,
                  event.time,
                  event.kind,
                  event.title,
                  event.size,
                  event.sealed.ciphertext(),
                  event.sealed.nonce()));
    } catch (DuplicateKeyException alreadyStored) {
    }
  }

  /**
   * @description 重试保存原始事件密文，不重放产生事件的操作
   * @author Freddy
   * @date 2026-11-13
   */
  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public synchronized void flushPending() {
    for (int i = 0; i < 200; i++) {
      var event = pending.peek();
      if (event == null) return;
      try {
        insert(event);
        pending.remove();
      } catch (DataAccessException
          | org.springframework.transaction.TransactionException unavailable) {
        return;
      }
    }
  }

  /**
   * @description 父业务事务持有连接时由独立线程写入，避免等待同一连接池中的第二个连接
   * @author Freddy
   * @date 2026-11-13
   */
  private void scheduleFlush() {
    if (scheduled.compareAndSet(false, true))
      try {
        writer.execute(
            () -> {
              try {
                flushPending();
              } finally {
                scheduled.set(false);
              }
            });
      } catch (java.util.concurrent.RejectedExecutionException stopping) {
        scheduled.set(false);
      }
  }

  /**
   * @description 查看详情时刷新已排队事件，业务事务内部不抢占第二个连接
   * @author Freddy
   * @date 2026-11-13
   */
  private void flushWhenSafe() {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) flushPending();
  }

  /**
   * @description 关闭前等待日志线程并尽量保存剩余密文，不重放业务操作
   * @author Freddy
   * @date 2026-11-13
   */
  @jakarta.annotation.PreDestroy
  public void stop() {
    writer.shutdown();
    try {
      writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    while (!pending.isEmpty()) {
      int before = pending.size();
      flushPending();
      if (pending.size() >= before) break;
    }
  }

  /**
   * @description 根据摘要记录约束查询范围，大载荷仅在单条详情接口解密
   * @author Freddy
   * @date 2026-11-13
   * @param trace 关联标识
   * @param step 步骤标识，空代表整个执行
   * @return 原始事件元数据
   */
  public List<Map<String, Object>> metadata(String trace, String step) {
    flushWhenSafe();
    if (trace == null) return List.of();
    String where = " where trace_id=?" + (step == null ? "" : " and step_key=?");
    Object[] args = step == null ? new Object[] {trace} : new Object[] {trace, step};
    return db.query(
        "select id,kind,title,occurred_at,payload_bytes,event_order from execution_log_raw"
            + where
            + " order by event_order,id",
        (r, n) ->
            Map.<String, Object>of(
                "id",
                r.getString("id"),
                "kind",
                r.getString("kind"),
                "title",
                r.getString("title"),
                "occurredAt",
                r.getObject("occurred_at", OffsetDateTime.class).toString(),
                "payloadBytes",
                r.getLong("payload_bytes")),
        args);
  }

  /**
   * @description 校验原始条目属于选定摘要的执行和步骤后解密返回
   * @author Freddy
   * @date 2026-11-13
   * @param trace 摘要所属关联标识
   * @param step 摘要步骤
   * @param id 原始条目标识
   * @return 原始载荷
   */
  public Map<String, Object> payload(String trace, String step, String id) {
    flushWhenSafe();
    if (trace == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "没有原始详情");
    var args = new ArrayList<Object>(List.of(id, trace));
    String where = " where id=? and trace_id=?";
    if (step != null) {
      where += " and step_key=?";
      args.add(step);
    }
    var rows =
        db.query(
            "select payload_ciphertext,payload_nonce from execution_log_raw" + where,
            (r, n) -> new byte[][] {r.getBytes(1), r.getBytes(2)},
            args.toArray());
    if (rows.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "原始条目不存在或不属于当前步骤");
    byte[] bytes = secrets.open(rows.getFirst()[0], rows.getFirst()[1]);
    try {
      return Map.of(
          "id",
          id,
          "payload",
          rawJson.readValue(
              bytes, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    } catch (Exception failure) {
      throw new IllegalStateException("原始日志读取失败", failure);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }
}
