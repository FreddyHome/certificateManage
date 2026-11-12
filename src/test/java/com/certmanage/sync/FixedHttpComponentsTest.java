package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.certmanage.certificate.CertificateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description 验证固定HTTP组件配置不可被通用HTTP字段覆盖
 * @author Freddy
 * @date 2026-11-12
 */
class FixedHttpComponentsTest {
  /**
   * @description 重载组件固定GET路径、参数和无请求体
   * @author Freddy
   * @date 2026-11-12
   */
  @Test
  void reloadOverridesInjectedRequest() {
    var fixed =
        new FixedHttpComponents(
            mock(JdbcTemplate.class), mock(CertificateService.class), new ObjectMapper());
    var config =
        new LinkedHashMap<String, Object>(
            Map.of(
                "httpComponent",
                "RELOAD_KEYSTORE",
                "keyStoreName",
                "a b",
                "type",
                "JKS",
                "path",
                "/evil",
                "method",
                "DELETE",
                "body",
                "secret",
                "certificateId",
                7));
    fixed.prepare(config, new ArrayList<>(), true);
    assertEquals("GET", config.get("method"));
    assertEquals("/wm/is/cert/reload", config.get("path"));
    assertEquals("NONE", config.get("bodyMode"));
    assertEquals(Map.of("keyStoreName", "a b", "type", "JKS"), config.get("query"));
    assertFalse(config.containsKey("body"));
    assertFalse(config.containsKey("certificateId"));
  }

  /**
   * @description 固定客户、用途查询按整链有效候选生成根到终端的精确DER并冻结引用
   * @author Freddy
   * @date 2026-11-12
   * @throws Exception 模拟证书编码或JSON解析失败
   */
  @Test
  void tnSelectsValidChainAndFreezesDer() throws Exception {
    var db = mock(JdbcTemplate.class);
    var certs = mock(CertificateService.class);
    var json = new ObjectMapper();
    when(db.queryForList("select external_id,id_type from customers where id=?", 5L))
        .thenReturn(List.of(Map.of("EXTERNAL_ID", "partner", "ID_TYPE", "AS2")));
    when(db.queryForList(contains("select a.id"), eq(Long.class), eq(5L), eq("AS2")))
        .thenReturn(List.of(90L, 80L));
    when(db.queryForList(
            "select material_id from asset_chain_nodes where asset_id=? order by node_position",
            Long.class,
            90L))
        .thenReturn(List.of(9L));
    when(db.queryForList(
            "select material_id from asset_chain_nodes where asset_id=? order by node_position",
            Long.class,
            80L))
        .thenReturn(List.of(1L, 2L));
    var expired = mock(java.security.cert.X509Certificate.class);
    doThrow(new java.security.cert.CertificateExpiredException()).when(expired).checkValidity();
    when(certs.material(9L)).thenReturn(expired);
    for (long material : List.of(1L, 2L)) {
      var cert = mock(java.security.cert.X509Certificate.class);
      when(cert.getEncoded()).thenReturn(new byte[] {(byte) material, 42});
      when(certs.material(material)).thenReturn(cert);
    }
    var c =
        new LinkedHashMap<String, Object>(
            Map.of(
                "httpComponent",
                "UPDATE_TN_PARTNER",
                "customerId",
                5L,
                "purpose",
                "AS2",
                "body",
                "evil",
                "id",
                "evil"));
    var refs = new ArrayList<Map<String, Object>>();
    new FixedHttpComponents(db, certs, json).prepare(c, refs, true);
    var body = json.readTree((String) c.get("body"));
    assertEquals("partner", body.path("id").asText());
    assertEquals("AS2", body.path("idType").asText());
    assertEquals(1, body.path("certChain").get(0).path("index").asInt());
    assertEquals("ASo=", body.path("certChain").get(0).path("certificateBase64").asText());
    assertEquals("Aio=", body.path("certChain").get(1).path("certificateBase64").asText());
    assertEquals(80L, refs.getFirst().get("assetId"));
    assertEquals(2L, refs.getLast().get("materialId"));
    assertEquals("/wm/tn/partner/cert/update", c.get("path"));
    when(db.queryForList(contains("select a.id"), eq(Long.class), eq(5L), eq("AS2")))
        .thenReturn(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedHttpComponents(db, certs, json).prepare(c, new ArrayList<>(), true));
    c.put("customerId", 6L);
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedHttpComponents(db, certs, json).prepare(c, new ArrayList<>(), true));
    c.put("purpose", "invalid");
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedHttpComponents(db, certs, json).prepare(c, new ArrayList<>(), true));
  }
}
