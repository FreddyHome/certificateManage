package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * @description 验证失败写库只重试已加密事件，不丢失原始字段或重复保存
 * @author Freddy
 * @date 2026-11-13
 */
class RawExecutionLogRetryTest {
  @TempDir Path folder;

  /**
   * @description 数据库恢复后保存同一事件密文，重复刷新不重复写入
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 密钥或事件解码失败
   */
  @Test
  void retriesEncryptedRecordOnceAfterDatabaseRecovery() throws Exception {
    var unavailable = new AtomicBoolean(true);
    var persisted = new ArrayList<Object[]>();
    var db =
        new JdbcTemplate() {
          @Override
          public int update(String sql, Object... args) {
            if (unavailable.get()) throw new DataAccessResourceFailureException("test outage");
            persisted.add(args.clone());
            return 1;
          }
        };
    var manager =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        };
    var key = new SecretStore(folder.resolve("master.key").toString());
    var json = new ObjectMapper();
    var log = new RawExecutionLog(db, json, key, manager);
    String trace =
        log.application("ORIGINAL", "raw-retry-marker", Map.of("body", "original body"), "step");
    assertTrue(persisted.isEmpty());
    log.flushPending();
    assertTrue(persisted.isEmpty());
    unavailable.set(false);
    log.flushPending();
    log.flushPending();
    assertEquals(1, persisted.size());
    Object[] row = persisted.getFirst();
    assertEquals(trace, row[1]);
    assertEquals("step", row[2]);
    byte[] ciphertext = (byte[]) row[8];
    assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("raw-retry-marker"));
    var original = json.readTree(key.open(ciphertext, (byte[]) row[9]));
    assertEquals("raw-retry-marker", original.path("message").asText());
    assertEquals("original body", original.path("details").path("body").asText());
  }

  /**
   * @description 事务管理器获取连接失败时只排队原始事件，不传播到远端操作
   * @author Freddy
   * @date 2026-11-13
   * @throws Exception 密钥初始化失败
   */
  @Test
  void transactionInfrastructureFailureIsQueued() throws Exception {
    var unavailable = new AtomicBoolean(true);
    var persisted = new ArrayList<Object[]>();
    var db =
        new JdbcTemplate() {
          @Override
          public int update(String sql, Object... args) {
            persisted.add(args.clone());
            return 1;
          }
        };
    var manager =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (unavailable.get())
              throw new CannotCreateTransactionException("test pool unavailable");
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        };
    var log =
        new RawExecutionLog(
            db,
            new ObjectMapper(),
            new SecretStore(folder.resolve("master.key").toString()),
            manager);
    assertDoesNotThrow(() -> log.application("TEST", "original", Map.of(), null));
    assertTrue(persisted.isEmpty());
    unavailable.set(false);
    log.flushPending();
    assertEquals(1, persisted.size());
  }

  @Test
  void largeBytesSurviveRestartWithoutPlaintextSpool() throws Exception {
    var unavailable = new AtomicBoolean(true);
    var rows = new ArrayList<Object[]>();
    var db =
        new JdbcTemplate() {
          @Override
          public int update(String sql, Object... args) {
            if (unavailable.get()) throw new DataAccessResourceFailureException("down");
            rows.add(args.clone());
            return 1;
          }
        };
    var manager =
        new PlatformTransactionManager() {
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          public void commit(TransactionStatus status) {}

          public void rollback(TransactionStatus status) {}
        };
    var key = new SecretStore(folder.resolve("large-master.key").toString());
    var mapper = new ObjectMapper();
    mapper
        .getFactory()
        .setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(32 * 1024 * 1024)
                .build());
    byte[] body = new byte[16 * 1024 * 1024];
    new Random(42).nextBytes(body);
    var first = new RawExecutionLog(db, mapper, key, manager);
    String trace = first.application("LARGE", "secret-marker", Map.of("bodyBase64", body), null);
    first.stop();
    assertTrue(rows.isEmpty());
    try (var paths = java.nio.file.Files.walk(folder)) {
      for (var path : paths.filter(p -> p.toString().endsWith(".audit")).toList())
        assertFalse(
            new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.ISO_8859_1)
                .contains("secret-marker"));
    }
    var restored = new RawExecutionLog(db, mapper, key, manager);
    unavailable.set(false);
    restored.flushPending();
    restored.flushPending();
    assertEquals(1, rows.size());
    assertEquals(trace, rows.getFirst()[1]);
    var row = rows.getFirst();
    byte[] compressed = key.open((byte[]) row[8], (byte[]) row[9]);
    assertEquals(31, compressed[0] & 255);
    var tree =
        mapper.readTree(
            new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed)));
    assertArrayEquals(body, tree.path("details").path("bodyBase64").binaryValue());
    restored.stop();
  }
}
