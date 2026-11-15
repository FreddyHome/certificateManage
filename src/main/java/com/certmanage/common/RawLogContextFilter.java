package com.certmanage.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * @description 将同一请求内的服务器测试和操作日志关联，日志查询自身不采集详情
 * @author Freddy
 * @date 2026-11-13
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RawLogContextFilter extends OncePerRequestFilter {
  private final RawExecutionLog raw;

  /**
   * @description 注入原始日志上下文服务
   * @author Freddy
   * @date 2026-11-13
   * @param raw 原始日志服务
   */
  public RawLogContextFilter(RawExecutionLog raw) {
    this.raw = raw;
  }

  /**
   * @description 排除静态资源、会话探测及日志查询
   * @author Freddy
   * @date 2026-11-13
   * @param request 当前请求
   * @return 是否跳过采集
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/v1/")
        || path.startsWith("/api/v1/execution-logs")
        || path.equals("/api/v1/auth/me")
        || path.equals("/api/v1/auth/csrf");
  }

  /**
   * @description 在请求完成后可靠释放上下文，未处理异常保存完整堆栈
   * @author Freddy
   * @date 2026-11-13
   * @param request 请求
   * @param response 响应
   * @param chain 后续过滤链
   * @throws ServletException 请求处理失败
   * @throws IOException IO失败
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try (var scope = raw.begin()) {
      try {
        chain.doFilter(request, response);
      } catch (ServletException | IOException | RuntimeException failure) {
        RawExecutionLog.exception(failure);
        throw failure;
      }
    }
  }
}
