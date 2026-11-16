package com.certmanage.common;

import java.util.Map;

/**
 * @description 提供不含目标地址、凭据和业务正文的聚合性能快照
 * @author Freddy
 * @date 2026-11-16
 */
public interface PerformanceSource {
  /**
   * @description 返回固定的指标分组名，不使用用户输入构造
   * @author Freddy
   * @date 2026-11-16
   * @return 固定分组名称
   */
  String performanceName();

  /**
   * @description 获取线程安全且有界的聚合状态，不执行业务或远程调用
   * @author Freddy
   * @date 2026-11-16
   * @return 仅包含计数、耗时、容量和状态的快照
   */
  Map<String, Object> performanceSnapshot();
}
