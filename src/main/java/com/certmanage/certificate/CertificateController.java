package com.certmanage.certificate;

import static com.certmanage.common.Errors.require;

import com.certmanage.common.Audit;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * @description 证书管理 HTTP 入口，维护现有导入、导出和幂等请求契约
 * @author Freddy
 * @date 2026-11-12
 */
@RestController
@RequestMapping("/api/v1")
public class CertificateController {
  private final com.certmanage.common.IdempotentActions idem;
  private final CertificateService service;
  private final CertificateCrypto crypto;
  private final Audit audit;

  /**
   * @description 装配证书业务、密码学、审计和幂等操作组件
   * @author Freddy
   * @date 2026-11-12
   * @param s 证书业务服务
   * @param c 证书解析与摘要计算组件
   * @param a 业务审计组件
   * @param i 幂等操作组件
   */
  public CertificateController(
      CertificateService s,
      CertificateCrypto c,
      Audit a,
      com.certmanage.common.IdempotentActions i) {
    service = s;
    crypto = c;
    audit = a;
    idem = i;
  }

  /**
   * @description 按归属返回证书资产列表
   * @author Freddy
   * @date 2026-11-12
   * @param owner 归属类型 OWN 或 CUSTOMER
   * @return 证书列表，无记录时为空列表
   */
  @GetMapping("/certificates")
  public Object list(@RequestParam(defaultValue = "OWN") String owner) {
    return service.list(owner);
  }

  /**
   * @description 查询普通或归档证书分页摘要，保留旧全量接口契约
   * @author Freddy
   * @date 2026-11-16
   * @param owner 归属，普通默认OWN，归档可空
   * @param kind 可选证书层级
   * @param query CN或客户包含搜索
   * @param purpose 可选用途
   * @param status 可选有效期状态
   * @param page 从1开始的页码
   * @param size 每页1至100条
   * @param request 用于识别普通与归档范围的当前请求
   * @return 当前页条目、总数及分页参数
   * @throws IllegalArgumentException 筛选或分页参数不合法
   */
  @GetMapping({"/certificates/page", "/certificates/archived/page"})
  public Object page(
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String purpose,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      jakarta.servlet.http.HttpServletRequest request) {
    return service.page(
        owner,
        kind,
        query,
        purpose,
        status,
        page,
        size,
        request.getRequestURI().endsWith("/certificates/archived/page"));
  }

  /**
   * @description 按可选归属查询归档证书
   * @author Freddy
   * @date 2026-11-12
   * @param owner 可为空的归属类型
   * @return 归档证书列表
   * @throws IllegalArgumentException 归属不合法时抛出
   */
  @GetMapping("/certificates/archived")
  public Object archived(@RequestParam(required = false) String owner) {
    return service.archived(owner);
  }

  /**
   * @description 归档证书并保留原启用状态
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @param principal 当前操作账号
   * @throws IllegalArgumentException 证书不存在时抛出
   */
  @PostMapping("/certificates/{id}/archive")
  public void archive(@PathVariable long id, Principal principal) {
    service.archive(id, principal.getName());
  }

  /**
   * @description 恢复归档证书及其原启用状态
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @param principal 当前操作账号
   * @throws IllegalArgumentException 证书不存在时抛出
   */
  @PostMapping("/certificates/{id}/restore")
  public void restore(@PathVariable long id, Principal principal) {
    service.restore(id, principal.getName());
  }

  /**
   * @description 读取指定证书的展示详情
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @return 证书详情
   */
  @GetMapping("/certificates/{id}")
  public Object detail(@PathVariable long id) {
    return service.detail(id);
  }

  /**
   * @description 请求删除已归档的自有或客户证书，并传递当前操作人
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @param p 当前已认证操作人
   */
  @DeleteMapping("/certificates/{id}")
  public void delete(@PathVariable long id, Principal p) {
    service.deleteArchived(id, p.getName());
  }

  /**
   * @description 证书状态写入请求，启用字段必须显式提供
   * @author Freddy
   * @date 2026-11-12
   * @param enabled 是否启用；null 为非法请求
   */
  public record EnabledRequest(Boolean enabled) {}

  /**
   * @description 修改自有或客户证书的业务启用状态
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @param input 启用状态请求
   * @param principal 当前已认证操作账号
   * @throws IllegalArgumentException 字段缺失或证书不存在时抛出
   */
  @PatchMapping("/certificates/{id}/enabled")
  public void setEnabled(
      @PathVariable long id, @RequestBody EnabledRequest input, Principal principal) {
    require(input.enabled() != null, "请明确指定启用状态");
    service.setEnabled(id, input.enabled(), principal.getName());
  }

  /**
   * @description 校验证书存在后返回按层级排列的链节点
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @return 按根到终端排序的链节点
   */
  @GetMapping("/certificates/{id}/chain")
  public Object chain(@PathVariable long id) {
    service.detail(id);
    return service.chain(id);
  }

  /**
   * @description 查询指定子证书可用的签发者
   * @author Freddy
   * @date 2026-11-12
   * @param childKind 下级证书类型，默认 LEAF
   * @return 可用签发者列表
   */
  @GetMapping("/issuers")
  public Object issuers(@RequestParam(defaultValue = "LEAF") String childKind) {
    return service.issuers(childKind);
  }

  /**
   * @description 对请求生成稳定摘要并通过幂等入口签发证书
   * @author Freddy
   * @date 2026-11-12
   * @param b 现有接口约定的请求字段
   * @param requestKey 可为空的客户端幂等键
   * @param p 当前已认证操作人
   * @return 证书签发结果
   * @throws Exception 序列化、证书材料解析或导出处理失败时抛出
   */
  @PostMapping("/certificates/issue")
  public Object issue(
      @RequestBody Map<String, Object> b,
      @RequestHeader(value = "X-Idempotency-Key", required = false) String requestKey,
      Principal p)
      throws Exception {
    String digest =
        crypto.fingerprint(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(
                    com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                    true)
                .writeValueAsBytes(b));
    return idem.execute(
        p.getName(), "ISSUE", requestKey, digest, () -> service.issue(b, p.getName()));
  }

  /**
   * @description 解析完整证书材料；预览只验证，提交通过幂等入口保存
   * @author Freddy
   * @date 2026-11-12
   * @param owner 归属类型 OWN 或 CUSTOMER
   * @param mode 材料格式模式
   * @param purpose 证书用途 AS2、HTTPS 或 GENERAL
   * @param customer 所属客户名称；自有证书可不填
   * @param password 可选证书库或私钥解密密码
   * @param files 构成完整链的证书文件列表
   * @param key 可选终端私钥文件
   * @param req 当前 HTTP 请求，用于区分预览与提交并获取幂等键
   * @param p 当前已认证操作人
   * @return 预览信息或包含资产 ID 的保存结果
   * @throws Exception 序列化、证书材料解析或导出处理失败时抛出
   */
  @PostMapping(
      value = {"/imports/preview", "/imports/commit"},
      consumes = "multipart/form-data")
  public Object upload(
      @RequestParam String owner,
      @RequestParam String mode,
      @RequestParam String purpose,
      @RequestParam(required = false) String customer,
      @RequestParam(defaultValue = "") String password,
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam(required = false) MultipartFile key,
      jakarta.servlet.http.HttpServletRequest req,
      Principal p)
      throws Exception {
    require(Set.of("OWN", "CUSTOMER").contains(owner), "归属错误");
    var bytes = new ArrayList<byte[]>();
    for (var f : files) bytes.add(f.getBytes());
    var parsed =
        crypto.parse(
            mode, bytes, key == null ? null : key.getBytes(), password, owner.equals("OWN"));
    if (req.getRequestURI().endsWith("preview"))
      return Map.of(
          "chain",
          parsed.chain().stream().map(c -> crypto.cn(c)).toList(),
          "keyMatched",
          parsed.key() != null);
    String digest =
        crypto.fingerprint(
            (owner
                    + "|"
                    + com.certmanage.customer.CustomerNames.key(Objects.toString(customer, ""))
                    + "|"
                    + purpose
                    + "|"
                    + parsed.chain().stream()
                        .map(
                            c -> {
                              try {
                                return crypto.fingerprint(c.getEncoded());
                              } catch (Exception e) {
                                throw new IllegalStateException(e);
                              }
                            })
                        .toList())
                .getBytes(StandardCharsets.UTF_8));
    return idem.execute(
        p.getName(),
        "IMPORT",
        req.getHeader("X-Idempotency-Key"),
        digest,
        () -> service.saveImport(parsed, owner, customer, purpose, p.getName()));
  }

  /**
   * @description 按选定格式导出证书；含私钥格式限定自有资产并要求密码
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @param b 现有接口约定的请求字段
   * @param principal 当前已认证操作人
   * @return 带附件文件名和二进制正文的响应
   * @throws Exception 序列化、证书材料解析或导出处理失败时抛出
   */
  @PostMapping("/certificates/{id}/export")
  public ResponseEntity<byte[]> export(
      @PathVariable long id, @RequestBody Map<String, String> b, Principal principal)
      throws Exception {
    var d = service.detail(id);
    var chain = service.chainCertificates(id);
    String format = b.getOrDefault("format", "PEM");
    byte[] bytes;
    String ext;
    switch (format) {
      case "DER" -> {
        bytes = chain.getLast().getEncoded();
        ext = "cer";
      }
      case "PEM" -> {
        StringBuilder pem = new StringBuilder();
        for (var c : chain) pem.append(crypto.pem(c));
        bytes = pem.toString().getBytes(StandardCharsets.US_ASCII);
        ext = "pem";
      }
      case "P7B" -> {
        bytes =
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertPath(chain.reversed())
                .getEncoded("PKCS7");
        ext = "p7b";
      }
      case "P12" -> {
        require("OWN".equals(d.get("owner")), "客户证书不可导出私钥");
        String password = b.getOrDefault("password", "");
        require(password.length() >= 8, "导出密码至少8位");
        var key = service.key(id);
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(
            "certificate",
            key,
            password.toCharArray(),
            chain.reversed().toArray(X509Certificate[]::new));
        var out = new java.io.ByteArrayOutputStream();
        store.store(out, password.toCharArray());
        bytes = out.toByteArray();
        ext = "p12";
      }
      default -> throw new IllegalArgumentException("不支持的导出格式");
    }
    audit.record(
        principal.getName(),
        format.equals("P12") ? "PRIVATE_KEY_EXPORT" : "CERTIFICATE_EXPORT",
        "CERTIFICATE",
        id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=certificate-" + id + "." + ext)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bytes);
  }
}
