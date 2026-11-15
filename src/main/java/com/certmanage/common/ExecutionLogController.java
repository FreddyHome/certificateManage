package com.certmanage.common;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution-logs")
public class ExecutionLogController {
  private final ExecutionLog log;

  public ExecutionLogController(ExecutionLog log) {
    this.log = log;
  }

  @GetMapping
  public Object list(
      @RequestParam(required = false) String source,
      @RequestParam(required = false) Long runId,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size) {
    return log.list(source, runId, outcome, from, to, page, size);
  }

  /**
   * @description 按日志所属执行和步骤读取关联详情，响应禁止缓存
   * @author Freddy
   * @date 2026-11-13
   * @param id 摘要日志主键
   * @return 关联日志及原始事件元数据
   */
  @GetMapping("/{id}")
  public org.springframework.http.ResponseEntity<?> detail(@PathVariable long id) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(log.detail(id));
  }

  /**
   * @description 解密读取属于该条日志的完整原始载荷，响应禁止缓存
   * @author Freddy
   * @date 2026-11-13
   * @param id 摘要日志主键
   * @param rawId 原始事件标识
   * @return 原始字段、正文或异常堆栈
   */
  @GetMapping("/{id}/raw/{rawId}")
  public org.springframework.http.ResponseEntity<?> original(
      @PathVariable long id, @PathVariable String rawId) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(log.original(id, rawId));
  }
}
