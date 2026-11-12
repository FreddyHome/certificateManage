package com.certmanage.customer;

import java.text.Normalizer;
import java.util.Locale;

/**
 * @description 客户名称的统一归一化规则，供管理和证书导入共用
 * @author Freddy
 * @date 2026-11-12
 */
public final class CustomerNames {
  /**
   * @description 禁止实例化无状态名称规则类
   * @author Freddy
   * @date 2026-11-12
   */
  private CustomerNames() {}

  /**
   * @description 修剪首尾空格并按 NFKC 和固定语言规则生成客户唯一键
   * @author Freddy
   * @date 2026-11-12
   * @param name 非 null 的客户名称；空白名称返回空键
   * @return 归一化后的客户唯一键
   */
  public static String key(String name) {
    return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }
}
