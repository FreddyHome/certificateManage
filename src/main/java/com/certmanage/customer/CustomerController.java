package com.certmanage.customer;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * @description 客户管理 HTTP 入口，仅负责请求和响应转换，业务与事务交由服务
 * @author Freddy
 * @date 2026-11-12
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
  private final CustomerService service;

  /**
   * @description 注入客户业务服务
   * @author Freddy
   * @date 2026-11-12
   * @param service 客户服务
   */
  public CustomerController(CustomerService service) {
    this.service = service;
  }

  /**
   * @description 查询客户列表，保持原响应字段和排序
   * @author Freddy
   * @date 2026-11-12
   * @return 客户响应列表
   */
  @GetMapping
  public List<CustomerResponse> list() {
    return service.list();
  }

  /**
   * @description 创建客户，保持原接口仅返回客户名称的约定
   * @author Freddy
   * @date 2026-11-12
   * @param input 客户写入请求
   * @param principal 当前已认证操作人
   * @return 已保存的客户名称
   */
  @PostMapping
  public Map<String, String> add(@RequestBody CustomerRequest input, Principal principal) {
    return Map.of("name", service.add(input, principal.getName()));
  }

  /**
   * @description 编辑指定客户，响应体保持为空
   * @author Freddy
   * @date 2026-11-12
   * @param id 客户 ID
   * @param input 客户写入请求
   * @param principal 当前已认证操作人
   */
  @PatchMapping("/{id}")
  public void edit(@PathVariable long id, @RequestBody CustomerRequest input, Principal principal) {
    service.edit(id, input, principal.getName());
  }
}
