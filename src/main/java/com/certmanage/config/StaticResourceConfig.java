package com.certmanage.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * @description 按内容哈希区分静态资源缓存，保留安全过滤器对 API 的禁止缓存策略
 * @author Freddy
 * @date 2026-11-16
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {
  private static final Pattern HASHED_ASSET =
      Pattern.compile("/assets/[^/]+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+$");

  /**
   * @description 仅给静态资源设置缓存，HTML 和未带哈希文件每次重新验证
   * @author Freddy
   * @date 2026-11-16
   * @param registry MVC 拦截器注册表，不允许为空
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new StaticCacheInterceptor()).excludePathPatterns("/api/**");
  }

  /**
   * @description 在静态处理器写入响应前设置缓存头，避免大资源提交响应后无法修改
   * @author Freddy
   * @date 2026-11-16
   */
  private static class StaticCacheInterceptor implements HandlerInterceptor {
    /**
     * @description 只对存在的哈希构建资源长期缓存；其他静态路径每次重新验证
     * @author Freddy
     * @date 2026-11-16
     * @param request 当前 HTTP 请求，不允许为空
     * @param response 当前 HTTP 响应，不允许为空
     * @param handler 已匹配的 MVC 处理器，不允许为空
     * @return 始终继续原处理链
     */
    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      if (handler instanceof ResourceHttpRequestHandler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (HASHED_ASSET.matcher(path).matches()
            && new ClassPathResource("static" + path).exists()) {
          response.setHeader(
              HttpHeaders.CACHE_CONTROL,
              CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable().getHeaderValue());
        } else {
          response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().getHeaderValue());
        }
      }
      return true;
    }
  }
}
