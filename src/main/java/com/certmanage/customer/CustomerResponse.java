package com.certmanage.customer;

/**
 * @description 客户查询与业务操作结果，字段保持现有前端契约
 * @author Freddy
 * @date 2026-11-12
 * @param id 客户稳定标识
 * @param name 客户显示名称
 * @param description 描述，无值时为空字符串
 * @param externalId 外部标识，无值时为空字符串
 * @param idType 标识类型，无值时为空字符串
 */
public record CustomerResponse(
    long id, String name, String description, String externalId, String idType) {}
