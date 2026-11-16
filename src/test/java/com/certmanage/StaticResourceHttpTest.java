package com.certmanage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * @description 通过嵌入式 HTTP 容器验证静态缓存、文本压缩与敏感 API 缓存边界
 * @author Freddy
 * @date 2026-11-16
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:static-resource-http;DB_CLOSE_DELAY=-1",
      "app.encryption-key-file=target/static-resource-http-master.key"
    })
class StaticResourceHttpTest {
  @LocalServerPort private int port;

  /**
   * @description 哈希资源长期缓存且以 gzip 传输，普通资源和 HTML 需重新验证，匿名 API 不缓存
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception HTTP 通信或解压失败时抛出
   */
  @Test
  void cachesOnlyHashedAssetsAndCompressesText() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      var hashed = get(client, "/assets/cache-test-abcdefgh.js", true);
      assertEquals(200, hashed.statusCode());
      assertEquals(
          "max-age=31536000, public, immutable",
          hashed.headers().firstValue("Cache-Control").orElseThrow());
      assertEquals("gzip", hashed.headers().firstValue("Content-Encoding").orElseThrow());
      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(hashed.body()))) {
        String content = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(content.contains("content hash cache test"));
        assertTrue(content.length() > hashed.body().length);
      }
      assertEquals(
          "no-cache",
          get(client, "/assets/cache-test.js", false)
              .headers()
              .firstValue("Cache-Control")
              .orElseThrow());
      assertEquals(
          "no-cache",
          get(client, "/cache-test.html", false)
              .headers()
              .firstValue("Cache-Control")
              .orElseThrow());
      var missing = get(client, "/assets/missing-abcdefgh.js", false);
      assertEquals(404, missing.statusCode());
      assertFalse(missing.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
      var api = get(client, "/api/v1/auth/me", false);
      assertEquals(401, api.statusCode());
      assertTrue(api.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
      var csrf = get(client, "/api/v1/auth/csrf", false);
      assertEquals(200, csrf.statusCode());
      assertTrue(csrf.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
    }
  }

  /**
   * @description 使用真实 HTTP 请求检查传输响应头，不由客户端自动解压
   * @author Freddy
   * @date 2026-11-16
   * @param client HTTP 客户端，不允许为空
   * @param path 以斜杠开头的本地测试路径
   * @param gzip 是否请求 gzip 压缩
   * @return 包含原始传输字节的 HTTP 响应
   * @throws Exception HTTP 通信失败时抛出
   */
  private HttpResponse<byte[]> get(HttpClient client, String path, boolean gzip) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (gzip) builder.header("Accept-Encoding", "gzip");
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }
}
