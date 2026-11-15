package com.certmanage.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @description 将当前执行上下文产生的实际应用日志原样复制至加密详情
 * @author Freddy
 * @date 2026-11-13
 */
@Component
public class RawApplicationAppender {
  private final RawExecutionLog raw;
  private Logger root;
  private AppenderBase<ILoggingEvent> appender;

  /**
   * @description 注入原始事件服务
   * @author Freddy
   * @date 2026-11-13
   * @param raw 事件服务
   */
  public RawApplicationAppender(RawExecutionLog raw) {
    this.raw = raw;
  }

  /**
   * @description 注册只采集当前上下文的应用日志适配器
   * @author Freddy
   * @date 2026-11-13
   */
  @PostConstruct
  public void start() {
    var context = (LoggerContext) LoggerFactory.getILoggerFactory();
    root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    appender =
        new AppenderBase<>() {
          @Override
          protected void append(ILoggingEvent event) {
            if (!raw.isCurrent()) return;
            var data = new LinkedHashMap<String, Object>();
            data.put("logger", event.getLoggerName());
            data.put("level", event.getLevel().toString());
            data.put("thread", event.getThreadName());
            data.put("timestamp", event.getTimeStamp());
            data.put("message", event.getFormattedMessage());
            if (event.getThrowableProxy() != null)
              data.put("stackTrace", ThrowableProxyUtil.asString(event.getThrowableProxy()));
            RawExecutionLog.event("LOGGER", "应用日志", data);
          }
        };
    appender.setContext(context);
    appender.setName("raw-audit-" + System.identityHashCode(this));
    appender.start();
    root.addAppender(appender);
  }

  /**
   * @description 应用上下文关闭后移除适配器，避免重复采集
   * @author Freddy
   * @date 2026-11-13
   */
  @PreDestroy
  public void stop() {
    if (root != null && appender != null) {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}
