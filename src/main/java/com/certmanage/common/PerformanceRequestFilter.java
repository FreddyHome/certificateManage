package com.certmanage.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * @description 在鉴权外层统计API耗时与拒绝，不记录URL、参数、用户或正文
 * @author Freddy
 * @date 2026-11-16
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PerformanceRequestFilter extends OncePerRequestFilter {
  private final PerformanceMetrics metrics;

  /**
   * @description 注入有界指标聚合器
   * @author Freddy
   * @date 2026-11-16
   * @param metrics 聚合器
   */
  public PerformanceRequestFilter(PerformanceMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * @description 排除静态资源和指标自身轮询
   * @author Freddy
   * @date 2026-11-16
   * @param request 当前请求
   * @return 是否跳过
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/")
        || request.getRequestURI().equals("/api/v1/system/performance");
  }

  /**
   * @description 请求结束后按处理器模板累加，不改变异常或响应
   * @author Freddy
   * @date 2026-11-16
   * @param request 请求
   * @param response 响应
   * @param chain 后续链
   * @throws ServletException 原处理链异常
   * @throws IOException 原通信异常
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    boolean failed = true;
    try {
      chain.doFilter(request, response);
      failed = false;
    } finally {
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
      metrics.record(
          request.getMethod(),
          pattern == null ? null : pattern.toString(),
          status,
          System.nanoTime() - start);
    }
  }
}
