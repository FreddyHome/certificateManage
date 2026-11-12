package com.certmanage.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * @description 验证两个固定HTTP业务接口独立的响应判定及安全message传递
 * @author Freddy
 * @date 2026-11-12
 */
class FixedHttpTransportTest {
  /**
   * @description 实际HTTP响应的业务失败、缺失判定和两类成功结果均按契约处理
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception 本地HTTP服务启动或请求失败
   */
  @Test
  void fixedResponsesPreserveSafeMessages() throws Exception {
    var response = new AtomicReference<>("{\"isError\":\"false\",\"message\":\"reload complete\"}");
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(status.get(), bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.start();
    try {
      var service = mock(ServerService.class);
      when(service.enabled(1)).thenReturn(true);
      when(service.connection(1))
          .thenReturn(
              new HashMap<>(
                  Map.of(
                      "type",
                      "HTTP",
                      "baseUrl",
                      "http://127.0.0.1:" + server.getAddress().getPort(),
                      "connectTimeout",
                      2,
                      "readTimeout",
                      2)));
      var transport = new RemoteTransport(service, new ObjectMapper());
      var step =
          new HashMap<String, Object>(
              Map.of(
                  "httpComponent",
                  "RELOAD_KEYSTORE",
                  "method",
                  "GET",
                  "bodyMode",
                  "NONE",
                  "path",
                  "/wm/is/cert/reload"));
      assertEquals("reload complete", transport.execute(1, 1, step, null, "unused").get("message"));
      response.set("{\"isError\":true,\"message\":\"reload denied\"}");
      assertEquals(
          "reload denied",
          assertThrows(RemoteFailure.class, () -> transport.execute(1, 1, step, null, "unused"))
              .getMessage());
      response.set("{\"code\":0,\"message\":\"wrong contract\"}");
      assertThrows(RemoteFailure.class, () -> transport.execute(1, 1, step, null, "unused"));
      step.put("httpComponent", "UPDATE_TN_PARTNER");
      step.put("method", "POST");
      step.put("bodyMode", "JSON");
      step.put("body", "{}");
      assertEquals("wrong contract", transport.execute(1, 1, step, null, "unused").get("message"));
      response.set("{\"code\":\"0\",\"message\":\"success\"}");
      assertEquals("success", transport.execute(1, 1, step, null, "unused").get("message"));
      response.set("{\"code\":\"1\",\"message\":\"TN denied\"}");
      assertEquals(
          "TN denied",
          assertThrows(RemoteFailure.class, () -> transport.execute(1, 1, step, null, "unused"))
              .getMessage());
      status.set(503);
      response.set("{\"code\":0,\"message\":\"service unavailable\"}");
      var failure =
          assertThrows(RemoteFailure.class, () -> transport.execute(1, 1, step, null, "unused"));
      assertEquals("HTTP_STATUS", failure.code());
      assertTrue(failure.getMessage().contains("service unavailable"));
      step.put("httpComponent", "RELOAD_KEYSTORE");
      response.set("{\"isError\":false,\"message\":\"reload unavailable\"}");
      failure =
          assertThrows(RemoteFailure.class, () -> transport.execute(1, 1, step, null, "unused"));
      assertEquals("HTTP_STATUS", failure.code());
      assertTrue(failure.getMessage().contains("reload unavailable"));
      response.set("<html>service unavailable</html>");
      assertEquals(
          "HTTP_STATUS",
          assertThrows(RemoteFailure.class, () -> transport.execute(1, 1, step, null, "unused"))
              .code());
    } finally {
      server.stop(0);
    }
  }

  /**
   * @description 嵌套秘密请求头、配置口令和控制字符在业务日志消息中均被清理
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void recursivelyRedactsSecretHeaderValues() {
    var config =
        Map.<String, Object>of(
            "password",
            "password-value",
            "secretHeaders",
            Map.of("X-Token", List.of("header-value", Map.of("nested", "nested-value"))));
    String message =
        RemoteTransport.safeBusinessMessage(
            "denied password-value header-value nested-value\n" + "A".repeat(100), config);
    assertFalse(message.contains("password-value"));
    assertFalse(message.contains("header-value"));
    assertFalse(message.contains("nested-value"));
    assertFalse(message.contains("\n"));
    assertFalse(message.contains("A".repeat(100)));
    assertEquals(500, RemoteTransport.safeBusinessMessage("失败".repeat(500), Map.of()).length());
  }
}
