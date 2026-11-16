package com.certmanage.sync;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * @description 独立计算业务计划预览，不保存任务或触发同步，沿用API认证及CSRF保护
 * @author Freddy
 * @date 2026-11-16
 */
@RestController
@RequestMapping("/api/v1/sync-tasks")
public class SchedulePreviewController {
  /**
   * @description 校验业务参数并返回最近三次计划时间，不依赖证书和步骤完整性
   * @author Freddy
   * @date 2026-11-16
   * @param schedule 业务计划参数
   * @return 规范化计划、中文摘要及执行时间
   * @throws IllegalArgumentException 计划参数无效
   */
  @PostMapping("/schedule-preview")
  public Map<String, Object> preview(@RequestBody Map<String, Object> schedule) {
    return SchedulePlan.normalize(schedule).preview();
  }
}
