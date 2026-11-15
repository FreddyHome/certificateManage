package com.certmanage.common;

import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;

/**
 * @description 在已有异常处理器转换响应之前保存原始异常，不改变接口错误语义
 * @author Freddy
 * @date 2026-11-13
 */
@Component
public class RawExceptionObserver implements HandlerExceptionResolver, Ordered {
  /**
   * @description 优先采集异常
   * @author Freddy
   * @date 2026-11-13
   * @return 最高优先级
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  /**
   * @description 保存原始堆栈后继续原有异常解析链
   * @author Freddy
   * @date 2026-11-13
   * @param request 请求
   * @param response 响应
   * @param handler 处理器
   * @param ex 原始异常
   * @return 空表示交由现有处理器处理
   */
  @Override
  public ModelAndView resolveException(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    RawExecutionLog.exception(ex);
    return null;
  }
}
