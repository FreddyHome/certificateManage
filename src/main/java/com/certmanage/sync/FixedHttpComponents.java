package com.certmanage.sync;

import static com.certmanage.common.Errors.require;
import static com.certmanage.sync.SyncData.*;

import com.certmanage.certificate.CertificateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * @description 生成固定业务HTTP请求，在执行快照形成时冻结客户标识、证书DER及引用
 * @author Freddy
 * @date 2026-11-12
 */
@Component
public class FixedHttpComponents {
  private final JdbcTemplate db;
  private final CertificateService certs;
  private final ObjectMapper json;

  /**
   * @description 注入固定组件使用的客户查询、证书服务及JSON编码器
   * @author Freddy
   * @date 2026-11-12
   * @param db 数据库访问器
   * @param certs 证书服务
   * @param json JSON编码器
   */
  public FixedHttpComponents(JdbcTemplate db, CertificateService certs, ObjectMapper json) {
    this.db = db;
    this.certs = certs;
    this.json = json;
  }

  /**
   * @description 归一化固定请求并在严格校验时选择整链有效且叶子到期最晚的客户证书
   * @author Freddy
   * @date 2026-11-12
   * @param c 可修改的组件配置
   * @param refs 执行快照证书引用集合
   * @param strict 是否校验并冻结可执行请求
   * @throws IllegalArgumentException 组件、客户标识、用途或有效证书缺失
   */
  public void prepare(Map<String, Object> c, List<Map<String, Object>> refs, boolean strict) {
    String component = str(c, "httpComponent", "GENERIC");
    require(
        Set.of("GENERIC", "RELOAD_KEYSTORE", "UPDATE_TN_PARTNER").contains(component),
        "HTTP业务组件类型不合法");
    c.put("httpComponent", component);
    if (component.equals("GENERIC")) return;
    for (String field :
        List.of(
            "sourceStep",
            "certificateId",
            "exportFormat",
            "body",
            "query",
            "fields",
            "headers",
            "jsonPath",
            "expectedValue",
            "successCodes",
            "id",
            "idType",
            "certChain",
            "contentType")) c.remove(field);
    if (component.equals("RELOAD_KEYSTORE")) {
      String name = str(c, "keyStoreName", "").trim(), type = str(c, "type", "").trim();
      if (strict) require(!name.isEmpty() && !type.isEmpty(), "请填写Keystore名称和类型");
      require(
          name.length() <= 200
              && type.length() <= 100
              && !name.codePoints().anyMatch(Character::isISOControl)
              && !type.codePoints().anyMatch(Character::isISOControl),
          "Keystore名称或类型不合法");
      c.put("method", "GET");
      c.put("bodyMode", "NONE");
      c.put("path", "/wm/is/cert/reload");
      c.put("query", Map.of("keyStoreName", name, "type", type));
      return;
    }
    c.put("method", "POST");
    c.put("bodyMode", "JSON");
    c.put("path", "/wm/tn/partner/cert/update");
    String purpose = str(c, "purpose", "");
    require(
        purpose.isEmpty() && !strict || Set.of("AS2", "HTTPS", "GENERAL").contains(purpose),
        "请选择有效的客户证书用途");
    if (!strict) return;
    long customer = id(c, "customerId");
    var customers =
        db.queryForList("select external_id,id_type from customers where id=?", customer);
    require(customers.size() == 1, "客户不存在");
    String external = Objects.toString(customers.getFirst().get("EXTERNAL_ID"), ""),
        idType = Objects.toString(customers.getFirst().get("ID_TYPE"), "");
    require(!external.isBlank() && !idType.isBlank(), "客户的对外标识和标识类型必填");
    var candidates =
        db.queryForList(
            "select a.id from certificate_assets a join certificate_materials m on m.id=a.material_id where a.owner_type='CUSTOMER' and a.customer_id=? and a.purpose=? and a.deleted_at is null order by m.not_after desc,a.id desc",
            Long.class,
            customer,
            purpose);
    require(!candidates.isEmpty(), "该客户用途下没有证书");
    for (long asset : candidates) {
      var materials =
          db.queryForList(
              "select material_id from asset_chain_nodes where asset_id=? order by node_position",
              Long.class,
              asset);
      var chain = new ArrayList<Map<String, Object>>();
      try {
        require(!materials.isEmpty(), "证书链为空");
        int index = 1;
        for (long material : materials) {
          var certificate = certs.material(material);
          certificate.checkValidity();
          chain.add(
              Map.of(
                  "index",
                  index++,
                  "certificateBase64",
                  Base64.getEncoder().encodeToString(certificate.getEncoded())));
        }
      } catch (Exception invalid) {
        continue;
      }
      try {
        c.put(
            "body",
            json.writeValueAsString(Map.of("id", external, "idType", idType, "certChain", chain)));
      } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
        throw new IllegalArgumentException("客户证书请求编码失败", failure);
      }
      for (int i = 0; i < materials.size(); i++)
        refs.add(
            Map.of(
                "assetId",
                asset,
                "materialId",
                materials.get(i),
                "alias",
                "tn-chain-" + (i + 1),
                "role",
                "PUBLIC"));
      return;
    }
    throw new IllegalArgumentException("该客户用途下没有整条证书链均在有效期内的证书");
  }
}
