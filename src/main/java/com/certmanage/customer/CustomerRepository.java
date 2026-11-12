package com.certmanage.customer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * @description 封装客户表的持久化访问，事务由调用的客户业务服务控制
 * @author Freddy
 * @date 2026-11-12
 */
@Repository
public class CustomerRepository {
  private final JdbcTemplate db;

  /**
   * @description 注入客户表数据库访问组件
   * @author Freddy
   * @date 2026-11-12
   * @param db 应用数据库访问模板
   */
  public CustomerRepository(JdbcTemplate db) {
    this.db = db;
  }

  /**
   * @description 按归一化名称排序读取客户，不暴露数据库字段命名
   * @author Freddy
   * @date 2026-11-12
   * @return 客户列表；无记录时返回空列表
   */
  public List<CustomerResponse> list() {
    return db.query(
        "select id,customer_name,description,external_id,id_type from customers order by name_key",
        (r, n) ->
            new CustomerResponse(
                r.getLong("id"),
                r.getString("customer_name"),
                Objects.toString(r.getString("description"), ""),
                Objects.toString(r.getString("external_id"), ""),
                Objects.toString(r.getString("id_type"), "")));
  }

  /**
   * @description 按唯一名称键查询已有客户
   * @author Freddy
   * @date 2026-11-12
   * @param key 已归一化的客户名称键
   * @return 客户 ID，不存在时为空
   */
  public Optional<Long> findId(String key) {
    return db.queryForList("select id from customers where name_key=?", Long.class, key).stream()
        .findFirst();
  }

  /**
   * @description 插入客户并读取本次语句生成的 ID，不通过全表最大 ID 推断
   * @author Freddy
   * @date 2026-11-12
   * @param input 已通过业务校验的客户请求
   * @return 新客户 ID
   * @throws org.springframework.dao.DuplicateKeyException 客户名称键重复时抛出
   */
  public long insert(CustomerRequest input) {
    var key = new GeneratedKeyHolder();
    db.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "insert into customers(customer_name,name_key,description,external_id,id_type) values(?,?,?,?,?)",
                  new String[] {"id"});
          statement.setString(1, input.name());
          statement.setString(2, CustomerNames.key(input.name()));
          statement.setString(3, input.description());
          statement.setString(4, input.externalId().isEmpty() ? null : input.externalId());
          statement.setString(5, input.idType().isEmpty() ? null : input.idType());
          return statement;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  /**
   * @description 更新客户字段，由服务判断目标是否存在
   * @author Freddy
   * @date 2026-11-12
   * @param id 待更新客户 ID
   * @param input 已校验的客户请求
   * @return 实际更新行数，客户不存在时为零
   * @throws org.springframework.dao.DuplicateKeyException 客户名称键重复时抛出
   */
  public int update(long id, CustomerRequest input) {
    return db.update(
        "update customers set customer_name=?,name_key=?,description=?,external_id=?,id_type=?,updated_at=current_timestamp where id=?",
        input.name(),
        CustomerNames.key(input.name()),
        input.description(),
        input.externalId().isEmpty() ? null : input.externalId(),
        input.idType().isEmpty() ? null : input.idType(),
        id);
  }
}
