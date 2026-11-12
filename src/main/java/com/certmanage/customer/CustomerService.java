package com.certmanage.customer;

import static com.certmanage.common.Errors.require;

import com.certmanage.common.Audit;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 统一客户管理和导入时的名称复用规则，维护客户变更与审计的事务边界
 * @author Freddy
 * @date 2026-11-12
 */
@Service
public class CustomerService {
  private final CustomerRepository repository;
  private final Audit audit;

  /**
   * @description 装配客户持久化和审计能力
   * @author Freddy
   * @date 2026-11-12
   * @param repository 客户持久化组件
   * @param audit 业务审计组件
   */
  public CustomerService(CustomerRepository repository, Audit audit) {
    this.repository = repository;
    this.audit = audit;
  }

  /**
   * @description 按统一排序返回客户列表
   * @author Freddy
   * @date 2026-11-12
   * @return 客户响应列表
   */
  @Transactional(readOnly = true)
  public List<CustomerResponse> list() {
    return repository.list();
  }

  /**
   * @description 新增客户，与操作审计在同一事务内提交
   * @author Freddy
   * @date 2026-11-12
   * @param input 客户请求，名称必填
   * @param actor 操作账号，必须是已存在用户
   * @return 已修剪的客户名称
   * @throws IllegalArgumentException 客户字段不合法时抛出
   * @throws DuplicateKeyException 归一化名称重复时抛出
   */
  @Transactional
  public String add(CustomerRequest input, String actor) {
    var clean = input.validated();
    repository.insert(clean);
    audit.record(actor, "CUSTOMER_CREATE", "CUSTOMER", clean.name());
    return clean.name();
  }

  /**
   * @description 修改客户；业务变更和操作审计共同回滚或提交
   * @author Freddy
   * @date 2026-11-12
   * @param id 客户 ID
   * @param input 客户请求，名称必填
   * @param actor 操作账号
   * @throws IllegalArgumentException 字段不合法或客户不存在时抛出
   * @throws DuplicateKeyException 名称与其他客户冲突时抛出
   */
  @Transactional
  public void edit(long id, CustomerRequest input, String actor) {
    var clean = input.validated();
    require(repository.update(id, clean) == 1, "客户不存在");
    audit.record(actor, "CUSTOMER_EDIT", "CUSTOMER", id);
  }

  /**
   * @description 在证书导入事务中复用归一化名称相同的客户；不存在则创建，保持原导入审计方式
   * @author Freddy
   * @date 2026-11-12
   * @param name 要复用或创建的客户名称
   * @return 客户稳定 ID
   * @throws IllegalArgumentException 客户名称为空或超长时抛出
   */
  @Transactional
  public long findOrCreateForImport(String name) {
    var clean = new CustomerRequest(name, "", "", "").validated();
    String key = CustomerNames.key(clean.name());
    var existing = repository.findId(key);
    if (existing.isPresent()) return existing.get();
    try {
      return repository.insert(clean);
    } catch (DuplicateKeyException conflict) {
      // 与管理入口并发创建时复用唯一约束已确认的客户。
      return repository.findId(key).orElseThrow(() -> conflict);
    }
  }
}
