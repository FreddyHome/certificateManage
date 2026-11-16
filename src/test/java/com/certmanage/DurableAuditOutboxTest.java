package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import com.certmanage.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableAuditOutboxTest {
  @TempDir Path folder;

  public record Event(String text) {}

  @Test
  void encryptedRecoveryAndFailureRetention() throws Exception {
    var key = new SecretStore(folder.resolve("master.key").toString());
    var json = new ObjectMapper();
    var dir = folder.resolve("spool");
    var first = new DurableAuditOutbox<Event>(dir, json, key, Event.class, 1024 * 1024);
    first.enqueue("one", new Event("secret-outbox-marker"));
    try (var paths = Files.list(dir)) {
      assertFalse(
          new String(Files.readAllBytes(paths.findFirst().orElseThrow()))
              .contains("secret-outbox-marker"));
    }
    var recovered = new DurableAuditOutbox<Event>(dir, json, key, Event.class, 1024 * 1024);
    assertThrows(
        IllegalStateException.class,
        () ->
            recovered.drain(
                20,
                100000,
                events -> {
                  throw new IllegalStateException("db down");
                }));
    assertEquals(1, recovered.pendingCount());
    var seen = new ArrayList<Event>();
    recovered.drain(20, 100000, seen::addAll);
    assertEquals(List.of(new Event("secret-outbox-marker")), seen);
    assertEquals(0, recovered.pendingBytes());
  }

  @Test
  void boundedDiskAndByteBatch() throws Exception {
    var key = new SecretStore(folder.resolve("master.key").toString());
    var box =
        new DurableAuditOutbox<Event>(
            folder.resolve("bounded"), new ObjectMapper(), key, Event.class, 1024);
    box.enqueue("a", new Event("one"));
    box.enqueue("b", new Event("two"));
    assertEquals(1, box.drain(100, box.pendingBytes() / 2, list -> assertEquals(1, list.size())));
    assertEquals(1, box.pendingCount());
    assertThrows(
        IllegalStateException.class, () -> box.enqueue("too-big", new Event("x".repeat(2000))));
    assertEquals(1, box.pendingCount());
  }

  @Test
  void reservationPreventsConcurrentOvercommitAndReturnsUnusedBudget() throws Exception {
    var key = new SecretStore(folder.resolve("reserve.key").toString());
    var box =
        new DurableAuditOutbox<Event>(
            folder.resolve("reserved"), new ObjectMapper(), key, Event.class, 1024);
    try (var reservation = box.reserve(800)) {
      assertThrows(IllegalStateException.class, () -> box.reserve(300));
      box.enqueue("reserved-event", new Event("reserved"), reservation);
      assertEquals(1, box.pendingCount());
    }
    assertDoesNotThrow(() -> box.ensureCapacity(800));
  }

  @Test
  void databaseUrlSeparatesSharedMasterKey() throws Exception {
    var key = new SecretStore(folder.resolve("database.key").toString());
    var first =
        new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:outbox_first", "sa", ""));
    var second =
        new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:outbox_second", "sa", ""));
    assertNotEquals(
        DurableAuditOutbox.directory(first, key, "raw"),
        DurableAuditOutbox.directory(second, key, "raw"));
  }

  @Test
  void databaseConsumerDoesNotHoldProducerMonitor() throws Exception {
    var key = new SecretStore(folder.resolve("lock.key").toString());
    var box =
        new DurableAuditOutbox<Event>(
            folder.resolve("lock"), new ObjectMapper(), key, Event.class, 100000);
    box.enqueue("first", new Event("one"));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var drain =
          executor.submit(
              () ->
                  box.drain(
                      100,
                      100000,
                      events -> {
                        entered.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      executor
          .submit(() -> box.enqueue("second", new Event("two")))
          .get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertEquals(2, box.pendingCount());
      release.countDown();
      drain.get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(1, box.pendingCount());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void twoInstancesShareQuotaIndexAndSingleConsumer() throws Exception {
    var key = new SecretStore(folder.resolve("shared.key").toString());
    var mapper = new ObjectMapper();
    var dir = folder.resolve("shared");
    var first = new DurableAuditOutbox<Event>(dir, mapper, key, Event.class, 1024);
    var second = new DurableAuditOutbox<Event>(dir.resolve("."), mapper, key, Event.class, 1024);
    try (var reservation = first.reserve(800)) {
      assertThrows(IllegalStateException.class, () -> second.reserve(300));
    }
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    try {
      var a = executor.submit(() -> first.enqueue("first", new Event("one")));
      var b = executor.submit(() -> second.enqueue("second", new Event("two")));
      a.get();
      b.get();
      assertEquals(2, first.pendingCount());
      assertEquals(2, second.pendingCount());
      assertEquals(first.pendingBytes(), second.pendingBytes());
      var drainA =
          executor.submit(
              () ->
                  first.drain(
                      100, 1024, events -> events.forEach(e -> assertTrue(seen.add(e.text())))));
      var drainB =
          executor.submit(
              () ->
                  second.drain(
                      100, 1024, events -> events.forEach(e -> assertTrue(seen.add(e.text())))));
      assertEquals(2, drainA.get() + drainB.get());
      assertEquals(Set.of("one", "two"), seen);
      assertEquals(0, first.pendingBytes());
      assertEquals(0, second.pendingCount());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void externallyMissingFileIsDiagnosedOnceAndDoesNotBlockRemainingEvents() throws Exception {
    var key = new SecretStore(folder.resolve("missing.key").toString());
    var dir = folder.resolve("missing");
    var box = new DurableAuditOutbox<Event>(dir, new ObjectMapper(), key, Event.class, 1024);
    box.enqueue("missing", new Event("missing"));
    try (var paths = Files.list(dir)) {
      Files.delete(paths.findFirst().orElseThrow());
    }
    box.enqueue("kept", new Event("kept"));
    var seen = new ArrayList<Event>();
    assertDoesNotThrow(() -> box.drain(100, 1024, seen::addAll));
    assertEquals(List.of(new Event("kept")), seen);
    assertEquals(0, box.pendingCount());
    assertEquals(0, box.drain(100, 1024, seen::addAll));
    assertEquals(1, box.missingFiles());
  }

  @Test
  void otherIoFailuresRetainPendingRecord() throws Exception {
    var key = new SecretStore(folder.resolve("io.key").toString());
    var dir = folder.resolve("io");
    var box = new DurableAuditOutbox<Event>(dir, new ObjectMapper(), key, Event.class, 1024);
    box.enqueue("one", new Event("one"));
    Path file;
    try (var paths = Files.list(dir)) {
      file = paths.findFirst().orElseThrow();
    }
    Path backup = dir.resolve("saved.bin");
    Files.move(file, backup);
    Files.createDirectory(file);
    assertThrows(
        IllegalStateException.class,
        () -> box.drain(100, 1024, events -> fail("must not consume IO failure")));
    assertEquals(1, box.pendingCount());
    assertEquals(0, box.missingFiles());
    Files.delete(file);
    Files.move(backup, file);
    assertEquals(
        1, box.drain(100, 1024, events -> assertEquals(List.of(new Event("one")), events)));
  }

  @Test
  void separateProcessSpoolIsRestoredFromDisk() throws Exception {
    Path keyPath = folder.resolve("process.key"), dir = folder.resolve("process");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                SpoolWriter.class.getName(),
                keyPath.toString(),
                dir.toString())
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    } finally {
      process.destroyForcibly();
    }
    var restored =
        new DurableAuditOutbox<Event>(
            dir, new ObjectMapper(), new SecretStore(keyPath.toString()), Event.class, 1024);
    assertEquals(1, restored.pendingCount());
    assertEquals(
        1,
        restored.drain(
            100, 1024, events -> assertEquals(List.of(new Event("process-restart")), events)));
  }

  public static class SpoolWriter {
    public static void main(String[] args) throws Exception {
      var box =
          new DurableAuditOutbox<Event>(
              Path.of(args[1]), new ObjectMapper(), new SecretStore(args[0]), Event.class, 1024);
      box.enqueue("process", new Event("process-restart"));
    }
  }
}
