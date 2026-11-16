package com.certmanage.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 先强制加密落盘再确认接收的审计待写箱；数据库提交后才删除文件，重启只重放审计
 * @author Freddy
 * @date 2026-11-16
 */
public final class DurableAuditOutbox<T> {
  private final Path directory;
  private final ObjectMapper json;
  private final SecretStore secrets;
  private final Class<T> type;
  private final SharedState state;
  private static final int MAX_FILES = 10000;
  private static final int MAX_DIRECTORIES = 1024;
  private static final Map<Path, java.lang.ref.WeakReference<SharedState>> STATES = new HashMap<>();
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(DurableAuditOutbox.class);

  /**
   * @description 同进程同规范目录共享索引、配额和消费者锁，弱注册表避免历史测试目录持续占用内存
   * @author Freddy
   * @date 2026-11-16
   */
  private static final class SharedState {
    final LinkedHashMap<Path, Long> files = new LinkedHashMap<>();
    final Object drainLock = new Object();
    final long maxBytes;
    final Class<?> type;
    final SecretStore.Sealed keyCheck;
    volatile long bytes, reservedBytes, missingFiles;
    volatile int count;
    int reservedSlots;

    /**
     * @description 只在首次创建共享目录状态时恢复磁文索引，不保留事件载荷
     * @author Freddy
     * @date 2026-11-16
     * @param directory 已规范化真实目录
     * @param maxBytes 积压上限
     * @param type 事件类型
     * @param secrets 目录所属密钥
     * @throws java.io.IOException 文件读取失败
     */
    SharedState(Path directory, long maxBytes, Class<?> type, SecretStore secrets)
        throws java.io.IOException {
      this.maxBytes = maxBytes;
      this.type = type;
      keyCheck = secrets.seal(new byte[] {42});
      try (var paths = Files.list(directory)) {
        for (var path :
            paths.filter(p -> p.toString().endsWith(".audit")).limit(MAX_FILES + 1L).toList()) {
          long size = Files.size(path);
          files.put(path, size);
          bytes += size;
          if (files.size() > MAX_FILES) throw new IllegalStateException("审计积压文件数超过10000，需恢复数据库后处理");
        }
      }
      count = files.size();
    }
  }

  /**
   * @description 恢复目录中的密文文件，堆中只保存路径和字节数
   * @author Freddy
   * @date 2026-11-16
   * @param directory 隔离的数据源目录
   * @param json JSON编码器
   * @param secrets 主密钥服务
   * @param type 事件类型
   * @param maxBytes 磁盘积压上限
   */
  public DurableAuditOutbox(
      Path directory, ObjectMapper json, SecretStore secrets, Class<T> type, long maxBytes) {
    this.json = json.copy().findAndRegisterModules();
    this.secrets = secrets;
    this.type = type;
    this.json
        .getFactory()
        .setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(64 * 1024 * 1024)
                .build());
    try {
      Files.createDirectories(directory);
      this.directory = directory.toRealPath();
      synchronized (STATES) {
        STATES.entrySet().removeIf(entry -> entry.getValue().get() == null);
        var reference = STATES.get(this.directory);
        var shared = reference == null ? null : reference.get();
        if (shared == null) {
          if (STATES.size() >= MAX_DIRECTORIES) throw new IllegalStateException("活跃审计目录超过1024");
          shared = new SharedState(this.directory, maxBytes, type, secrets);
          STATES.put(this.directory, new java.lang.ref.WeakReference<>(shared));
        } else {
          if (shared.maxBytes != maxBytes || shared.type != type)
            throw new IllegalStateException("同一审计目录的类型和容量必须一致");
          byte[] check = secrets.open(shared.keyCheck.ciphertext(), shared.keyCheck.nonce());
          try {
            if (check.length != 1 || check[0] != 42) throw new IllegalStateException("审计目录密钥不一致");
          } finally {
            Arrays.fill(check, (byte) 0);
          }
        }
        state = shared;
      }
    } catch (Exception e) {
      throw new IllegalStateException("审计待写目录恢复失败", e);
    }
  }

  /**
   * @description 用数据源URL摘要隔离不同数据库，测试空数据源使用密钥目录作为隔离边界
   * @author Freddy
   * @date 2026-11-16
   * @param db 数据库
   * @param secrets 主密钥服务
   * @param lane 审计通道名
   * @return 隔离目录
   */
  public static Path directory(JdbcTemplate db, SecretStore secrets, String lane) {
    try {
      String identity = secrets.directory().toString();
      if (db.getDataSource() != null)
        try (var c = db.getDataSource().getConnection()) {
          identity = c.getMetaData().getURL();
        }
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return secrets.directory().resolve("audit-outbox").resolve(hash).resolve(lane);
    } catch (Exception e) {
      throw new IllegalStateException("审计数据源隔离失败", e);
    }
  }

  /**
   * @description 在不可重放的远端操作前检查待写磁盘预算
   * @author Freddy
   * @date 2026-11-16
   * @param requiredBytes 预留预算
   */
  public void ensureCapacity(long requiredBytes) {
    synchronized (state) {
      if (requiredBytes < 0
          || requiredBytes > state.maxBytes - state.bytes - state.reservedBytes
          || state.files.size() + state.reservedSlots >= MAX_FILES)
        throw new IllegalStateException("审计待写磁盘达到上限，请恢复数据库后重试");
    }
  }

  /**
   * @description 完整事件封装AES密文，强制写入临时文件后原子发布；失败不会确认接收
   * @author Freddy
   * @date 2026-11-16
   * @param id 幂等标识
   * @param event 不可变事件
   */
  public void enqueue(String id, T event) {
    enqueue(id, event, null);
  }

  /**
   * @description 使用操作已预留的空间落盘，实际字节和条数同时消耗租约
   * @author Freddy
   * @date 2026-11-16
   * @param id 幂等标识
   * @param event 不可变事件
   * @param reservation 操作预算，普通日志可为空
   */
  public void enqueue(String id, T event, Reservation reservation) {
    synchronized (state) {
      Path temporary = null;
      byte[] plain = null;
      try {
        String safe =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path target = directory.resolve(safe + ".audit");
        if (state.files.containsKey(target)) {
          try {
            Files.size(target);
            return;
          } catch (NoSuchFileException missing) {
            diagnoseMissing(target);
          }
        }
        var output = new java.io.ByteArrayOutputStream();
        var bounded =
            new java.io.FilterOutputStream(output) {
              private int encodedCount;

              @Override
              public void write(int value) throws java.io.IOException {
                if (++encodedCount > 32 * 1024 * 1024)
                  throw new java.io.IOException("单条审计编码超过32MiB");
                out.write(value);
              }

              @Override
              public void write(byte[] value, int offset, int length) throws java.io.IOException {
                if ((long) encodedCount + length > 32 * 1024 * 1024)
                  throw new java.io.IOException("单条审计编码超过32MiB");
                encodedCount += length;
                out.write(value, offset, length);
              }
            };
        json.writeValue(bounded, event);
        plain = output.toByteArray();
        var sealed = secrets.seal(plain);
        int size = 12 + sealed.ciphertext().length;
        if (reservation != null) {
          if (reservation.owner != state || reservation.slots <= 0 || reservation.remaining < size)
            throw new IllegalStateException("远端操作超出审计预留预算");
        } else ensureCapacity(size);
        temporary = Files.createTempFile(directory, "pending-", ".tmp");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
          for (byte[] part : List.of(sealed.nonce(), sealed.ciphertext())) {
            var buffer = java.nio.ByteBuffer.wrap(part);
            while (buffer.hasRemaining()) channel.write(buffer);
          }
          channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        state.files.put(target, (long) size);
        state.bytes += size;
        state.count = state.files.size();
        if (reservation != null) {
          reservation.remaining -= size;
          state.reservedBytes -= size;
          reservation.slots--;
          state.reservedSlots--;
        }
      } catch (Exception e) {
        throw new IllegalStateException("审计可靠落盘失败；禁止重放已完成的远端操作", e);
      } finally {
        if (plain != null) Arrays.fill(plain, (byte) 0);
        if (temporary != null)
          try {
            Files.deleteIfExists(temporary);
          } catch (Exception ignored) {
          }
      }
    }
  }

  /**
   * @description 按密文字节严格有界读取并交给单次数据库事务；数据库等待在生产者锁外，事务失败保留文件
   * @author Freddy
   * @date 2026-11-16
   * @param maxCount 条数限制
   * @param maxBatchBytes 每批密文字节限制
   * @param transaction 幂等批量数据库事务
   * @return 成功确认条数
   */
  public int drain(int maxCount, long maxBatchBytes, Consumer<List<T>> transaction) {
    if (maxCount <= 0 || maxBatchBytes <= 0) throw new IllegalArgumentException("审计批量条数和字节上限必须为正");
    synchronized (state.drainLock) {
      var selected = new ArrayList<Path>();
      long batchBytes = 0;
      synchronized (state) {
        for (var entry : state.files.entrySet()) {
          if (entry.getValue() > maxBatchBytes && selected.isEmpty())
            throw new IllegalStateException("单条审计超过本次批量字节预算");
          if (selected.size() >= maxCount || batchBytes + entry.getValue() > maxBatchBytes) break;
          selected.add(entry.getKey());
          batchBytes += entry.getValue();
        }
      }
      if (selected.isEmpty()) return 0;
      var events = new ArrayList<T>();
      var available = new ArrayList<Path>();
      try {
        for (var path : selected) {
          byte[] encoded;
          try {
            encoded = Files.readAllBytes(path);
          } catch (NoSuchFileException missing) {
            diagnoseMissing(path);
            continue;
          }
          byte[] plain =
              secrets.open(
                  Arrays.copyOfRange(encoded, 12, encoded.length), Arrays.copyOf(encoded, 12));
          try {
            events.add(json.readValue(plain, type));
            available.add(path);
          } finally {
            Arrays.fill(plain, (byte) 0);
          }
        }
        // 事务可能等待数据库连接，必须在待写箱锁外，生产者才能继续可靠落盘并结束业务事务。
        if (events.isEmpty()) return 0;
        transaction.accept(List.copyOf(events));
        synchronized (state) {
          for (var path : available) {
            if (!Files.deleteIfExists(path)) diagnoseMissing(path);
            Long size = state.files.remove(path);
            if (size != null) state.bytes -= size;
            state.count = state.files.size();
          }
        }
        return available.size();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException("审计待写恢复失败，文件已保留", e);
      }
    }
  }

  /**
   * @description 仅对实际缺失文件解除失效索引并记录诊断，其他IO错误保持重试数据；同一路径只报告一次
   * @author Freddy
   * @date 2026-11-16
   * @param path 已确认不存在的文件
   */
  private void diagnoseMissing(Path path) {
    synchronized (state) {
      Long size = state.files.remove(path);
      if (size != null) {
        state.bytes -= size;
        state.count = state.files.size();
        state.missingFiles++;
        LOG.error("审计待写文件被外部移除，已解除失效索引；请检查目录清理或外部操作，累计缺失 {} 条", state.missingFiles);
      }
    }
  }

  /**
   * @description 返回外部移除或测试清理造成的缺失文件累计数，不暴露事件内容
   * @author Freddy
   * @date 2026-11-16
   * @return 缺失文件数量
   */
  public long missingFiles() {
    return state.missingFiles;
  }

  /**
   * @description 原子预留并发远端操作所需磁盘预算
   * @author Freddy
   * @date 2026-11-16
   * @param size 本次操作最大编码字节预算
   * @return 可释放的预算租约
   */
  public Reservation reserve(long size) {
    synchronized (state) {
      ensureCapacity(size);
      if (state.files.size() + state.reservedSlots + 128 > MAX_FILES)
        throw new IllegalStateException("审计待写条数达到上限");
      state.reservedBytes += size;
      state.reservedSlots += 128;
      return new Reservation(size);
    }
  }

  /**
   * @description 为一次不可重放操作保留磁盘字节和128个事件槽位
   * @author Freddy
   * @date 2026-11-16
   */
  public final class Reservation implements AutoCloseable {
    private final SharedState owner = state;
    private long remaining;
    private int slots = 128;

    /**
     * @description 保存已在待写箱锁内确认的预算
     * @author Freddy
     * @date 2026-11-16
     * @param remaining 初始字节预算
     */
    private Reservation(long remaining) {
      this.remaining = remaining;
    }

    /**
     * @description 释放尚未使用的字节和条数，重复关闭保持幂等
     * @author Freddy
     * @date 2026-11-16
     */
    public void close() {
      synchronized (state) {
        state.reservedBytes -= remaining;
        remaining = 0;
        state.reservedSlots -= slots;
        slots = 0;
      }
    }
  }

  /**
   * @description 返回尚未确认的条数
   * @author Freddy
   * @date 2026-11-16
   * @return 待写条数
   */
  public int pendingCount() {
    return state.count;
  }

  /**
   * @description 返回磁盘密文积压字节
   * @author Freddy
   * @date 2026-11-16
   * @return 待写字节
   */
  public long pendingBytes() {
    return state.bytes;
  }

  /**
   * @description 返回当前远端操作尚未消费的磁盘预留字节
   * @author Freddy
   * @date 2026-11-16
   * @return 预留字节
   */
  public long reservedBytes() {
    return state.reservedBytes;
  }
}
