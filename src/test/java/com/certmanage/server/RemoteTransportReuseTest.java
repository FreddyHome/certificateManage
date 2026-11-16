package com.certmanage.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;

class RemoteTransportReuseTest {
  @Test
  void reusesVersionAndClosesCache() throws Exception {
    var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    target.createContext(
        "/",
        e -> {
          e.sendResponseHeaders(200, -1);
          e.close();
        });
    target.start();
    var server = mock(ServerService.class);
    when(server.enabled(1)).thenReturn(true);
    var config =
        Map.<String, Object>of(
            "type",
            "HTTP",
            "baseUrl",
            "http://127.0.0.1:" + target.getAddress().getPort(),
            "connectTimeout",
            1,
            "readTimeout",
            1);
    when(server.connection(anyLong())).thenReturn(config);
    var transport = new RemoteTransport(server, new ObjectMapper());
    try {
      transport.execute(1, 1, Map.of("path", "/", "bodyMode", "NONE"), null, "");
      transport.execute(1, 1, Map.of("path", "/", "bodyMode", "NONE"), null, "");
      assertEquals(1L, transport.performanceSnapshot().get("clientsCreated"));
      assertEquals(1L, transport.performanceSnapshot().get("clientsReused"));
      transport.execute(1, 2, Map.of("path", "/", "bodyMode", "NONE"), null, "");
      assertEquals(2L, transport.performanceSnapshot().get("clientsCreated"));
      for (int version = 3; version <= 66; version++)
        transport.execute(1, version, Map.of("path", "/", "bodyMode", "NONE"), null, "");
      assertEquals(64, transport.performanceSnapshot().get("cachedClients"));
      assertEquals(66L, transport.performanceSnapshot().get("clientsCreated"));
      when(server.enabled(1)).thenReturn(false);
      assertThrows(
          IllegalArgumentException.class,
          () -> transport.execute(1, 1, Map.of("path", "/"), null, ""));
    } finally {
      transport.stop();
      target.stop(0);
    }
    assertEquals(0, transport.performanceSnapshot().get("cachedClients"));
  }

  @Test
  void shutdownDefersClosingInFlightResponse() throws Exception {
    var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    target.createContext(
        "/",
        e -> {
          try {
            e.sendResponseHeaders(200, 2);
            e.getResponseBody().write(1);
            e.getResponseBody().flush();
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            e.getResponseBody().write(2);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            e.close();
          }
        });
    target.start();
    var server = mock(ServerService.class);
    when(server.enabled(1)).thenReturn(true);
    when(server.connection(1))
        .thenReturn(
            Map.of(
                "type",
                "HTTP",
                "baseUrl",
                "http://127.0.0.1:" + target.getAddress().getPort(),
                "connectTimeout",
                1,
                "readTimeout",
                10));
    var transport = new RemoteTransport(server, new ObjectMapper());
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var request =
          executor.submit(
              () -> transport.execute(1, 1, Map.of("path", "/", "bodyMode", "NONE"), null, ""));
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      transport.stop();
      assertFalse(request.isDone());
      release.countDown();
      assertEquals(200, request.get(3, java.util.concurrent.TimeUnit.SECONDS).get("statusCode"));
    } finally {
      release.countDown();
      transport.stop();
      executor.shutdownNow();
      target.stop(0);
    }
  }
}
