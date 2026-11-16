package com.certmanage.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class ConfigSecrets {
  private final JdbcTemplate db;
  private final SecretStore store;
  private final ObjectMapper json;

  public ConfigSecrets(JdbcTemplate d, SecretStore s, ObjectMapper j) {
    db = d;
    store = s;
    json = j;
  }

  public Long save(Map<String, Object> values) {
    try {
      var sealed = store.seal(json.writeValueAsBytes(values));
      var key = new GeneratedKeyHolder();
      db.update(
          c -> {
            var p =
                c.prepareStatement(
                    "insert into sync_secret_bundles(ciphertext,nonce) values(?,?)",
                    new String[] {"id"});
            p.setBytes(1, sealed.ciphertext());
            p.setBytes(2, sealed.nonce());
            return p;
          },
          key);
      return key.getKey().longValue();
    } catch (Exception e) {
      throw new IllegalStateException("秘密保存失败", e);
    }
  }

  public Map<String, Object> read(Long id) {
    if (id == null) return new LinkedHashMap<>();
    return db.queryForObject(
        "select ciphertext,nonce from sync_secret_bundles where id=?",
        (r, n) -> {
          try {
            return json.readValue(
                store.open(r.getBytes(1), r.getBytes(2)),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
          } catch (Exception e) {
            throw new IllegalStateException("秘密读取失败", e);
          }
        },
        id);
  }

  /**
   * @description 兼容旧配置时一次读取并解密所需秘密，避免列表逐条查询。
   * @author Freddy
   * @date 2026-11-16
   * @param ids 需要补全标记的秘密版本
   * @return 按秘密版本编号分组的明文，仅在当前调用内使用
   */
  public Map<Long, Map<String, Object>> readBatch(Collection<Long> ids) {
    var result = new HashMap<Long, Map<String, Object>>();
    if (ids.isEmpty()) return result;
    var unique = new LinkedHashSet<>(ids);
    db.query(
        "select id,ciphertext,nonce from sync_secret_bundles where id in ("
            + String.join(",", Collections.nCopies(unique.size(), "?"))
            + ")",
        rs -> {
          try {
            result.put(
                rs.getLong(1),
                json.readValue(
                    store.open(rs.getBytes(2), rs.getBytes(3)),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
          } catch (Exception e) {
            throw new IllegalStateException("秘密读取失败", e);
          }
        },
        unique.toArray());
    return result;
  }
}
