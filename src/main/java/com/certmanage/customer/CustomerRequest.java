package com.certmanage.customer;

import static com.certmanage.common.Errors.require;

import java.util.Objects;

/**
 * @description 客户写入请求；在服务边界统一校验，避免依赖 HTTP 校验才能保证业务规则
 * @author Freddy
 * @date 2026-11-12
 * @param name 客户名称，校验时修剪首尾空格
 * @param description 可选描述，最大 2000 字符
 * @param externalId 可选外部标识，最大 200 字符
 * @param idType 可选标识类型，最大 100 字符
 */
public record CustomerRequest(String name, String description, String externalId, String idType) {
  /**
   * @description 校验数据库长度约束并归一化输入；可选字段为空时保留空字符串响应约定
   * @author Freddy
   * @date 2026-11-12
   * @return 校验完成的新请求对象
   * @throws IllegalArgumentException 必填项缺失或字段超长时抛出
   */
  public CustomerRequest validated() {
    String cleanName = Objects.toString(name, "").trim();
    require(!cleanName.isEmpty() && cleanName.length() <= 200, "客户名称必填且不超过200字");
    require(CustomerNames.key(cleanName).length() <= 300, "客户名称归一化后过长");
    String cleanDescription = Objects.toString(description, "");
    require(cleanDescription.length() <= 2000, "描述不超过2000字");
    return new CustomerRequest(
        cleanName,
        cleanDescription,
        optional(externalId, 200, "externalId"),
        optional(idType, 100, "idType"));
  }

  /**
   * @description 修剪可选字段并校验长度，空值转换为空字符串
   * @author Freddy
   * @date 2026-11-12
   * @param value 可为空的原始值
   * @param max 最大字符数
   * @param field 用于错误提示的字段名
   * @return 修剪后的字段值
   * @throws IllegalArgumentException 字段超过长度上限时抛出
   */
  private static String optional(String value, int max, String field) {
    String clean = Objects.toString(value, "").trim();
    require(clean.length() <= max, field + " exceeds maximum length " + max);
    return clean;
  }
}
