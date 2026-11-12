package com.certmanage.certificate;

import static com.certmanage.common.Errors.require;

import com.certmanage.common.*;
import com.certmanage.customer.CustomerService;
import java.security.*;
import java.security.cert.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description 管理证书资产、签发和导入事务，并通过客户服务复用所属客户
 * @author Freddy
 * @date 2026-11-12
 */
@Service
public class CertificateService {
  private final CustomerService customers;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final CertificateCrypto crypto;
  private final SecretStore secrets;
  private final Audit audit;

  /**
   * @description 装配证书持久化、加密、审计与客户业务能力
   * @author Freddy
   * @date 2026-11-12
   * @param d 数据库访问模板
   * @param t 业务事务模板
   * @param c 证书解析、签发与密钥匹配组件
   * @param s 主密钥加密与解密组件
   * @param a 业务审计组件
   * @param customers 客户业务服务
   */
  public CertificateService(
      JdbcTemplate d,
      TransactionTemplate t,
      CertificateCrypto c,
      SecretStore s,
      Audit a,
      CustomerService customers) {
    this.customers = customers;
    db = d;
    tx = t;
    crypto = c;
    secrets = s;
    audit = a;
  }

  /**
   * @description 按材料 ID 解析数据库中的 X.509 公钥证书
   * @author Freddy
   * @date 2026-11-12
   * @param id 公钥证书材料 ID
   * @return 解析完成的 X.509 证书
   */
  public X509Certificate material(long id) {
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(
                  new java.io.ByteArrayInputStream(
                      db.queryForObject(
                          "select certificate_der from certificate_materials where id=?",
                          byte[].class,
                          id)));
    } catch (Exception e) {
      throw new IllegalArgumentException("证书不存在或无法读取");
    }
  }

  /**
   * @description 读取并解密自有资产私钥，客户资产不提供此能力
   * @author Freddy
   * @date 2026-11-12
   * @param asset 证书资产 ID
   * @return 解密后的私钥
   */
  public PrivateKey key(long asset) {
    detail(asset);
    var r = db.queryForList("select * from private_keys where own_asset_id=?", asset);
    require(!r.isEmpty(), "未保存该证书私钥");
    try {
      return crypto.privateKey(
          secrets.open((byte[]) r.getFirst().get("CIPHERTEXT"), (byte[]) r.getFirst().get("NONCE")),
          new char[0]);
    } catch (Exception e) {
      throw new IllegalArgumentException("私钥读取失败");
    }
  }

  /**
   * @description 按指纹复用证书材料，不存在时保存材料并返回标识
   * @author Freddy
   * @date 2026-11-12
   * @param c 待处理的 X.509 公钥证书
   * @return 已有或新建的材料 ID
   */
  private long materialId(X509Certificate c) {
    try {
      String sha = crypto.fingerprint(c.getEncoded());
      var ids =
          db.queryForList(
              "select id from certificate_materials where fingerprint_sha256=?", Long.class, sha);
      if (!ids.isEmpty()) return ids.getFirst();
      db.update(
          "insert into certificate_materials(fingerprint_sha256,certificate_der,common_name,subject_dn,issuer_dn,serial_hex,not_before,not_after,public_key_algorithm,signature_algorithm,spki_sha256,is_ca,path_length,san_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          sha,
          c.getEncoded(),
          crypto.cn(c),
          c.getSubjectX500Principal().getName(),
          c.getIssuerX500Principal().getName(),
          c.getSerialNumber().toString(16),
          c.getNotBefore().toInstant().atOffset(ZoneOffset.UTC),
          c.getNotAfter().toInstant().atOffset(ZoneOffset.UTC),
          c.getPublicKey().getAlgorithm(),
          c.getSigAlgName(),
          crypto.fingerprint(c.getPublicKey().getEncoded()),
          c.getBasicConstraints() >= 0,
          c.getBasicConstraints() < 0 ? null : c.getBasicConstraints(),
          String.valueOf(c.getSubjectAlternativeNames()));
      return db.queryForObject(
          "select id from certificate_materials where fingerprint_sha256=?", Long.class, sha);
    } catch (Exception e) {
      throw new IllegalArgumentException("证书保存失败");
    }
  }

  /**
   * @description 依据基本约束与颁发者区分根、中间和终端证书
   * @author Freddy
   * @date 2026-11-12
   * @param c 待处理的 X.509 公钥证书
   * @return ROOT、INTERMEDIATE 或 LEAF
   */
  private String kind(X509Certificate c) {
    if (c.getBasicConstraints() < 0) return "LEAF";
    return c.getSubjectX500Principal().equals(c.getIssuerX500Principal()) ? "ROOT" : "INTERMEDIATE";
  }

  /**
   * @description 按归属及材料复用资产，保存链节点和经过匹配校验的加密私钥
   * @author Freddy
   * @date 2026-11-12
   * @param chain 按根到终端排列的完整证书链
   * @param index 当前证书在完整链中的零基位置
   * @param owner 归属类型 OWN 或 CUSTOMER
   * @param customer 客户 ID；自有证书为 null
   * @param purpose 证书用途 AS2、HTTPS 或 GENERAL
   * @param privateKey 可为空的终端私钥，非空时必须与证书匹配
   * @param source 证书来源类型
   * @param actor 操作用户数据库 ID
   * @return 已有或新建的资产 ID
   */
  private long saveAsset(
      List<X509Certificate> chain,
      int index,
      String owner,
      Long customer,
      String purpose,
      PrivateKey privateKey,
      String source,
      long actor) {
    var c = chain.get(index);
    long m = materialId(c);
    String scope = owner.equals("OWN") ? "OWN" : "CUSTOMER:" + customer;
    var ids =
        db.queryForList(
            "select id from certificate_assets where scope_key=? and material_id=?",
            Long.class,
            scope,
            m);
    if (!ids.isEmpty()) {
      db.update("update certificate_assets set deleted_at=null where id=?", ids.getFirst());
      return ids.getFirst();
    }
    Long issuer = index > 0 ? materialId(chain.get(index - 1)) : m;
    Long issuerAsset = null;
    if (index > 0 && owner.equals("OWN")) {
      var list =
          db.queryForList(
              "select id from certificate_assets where scope_key='OWN' and material_id=?",
              Long.class,
              issuer);
      if (!list.isEmpty()) issuerAsset = list.getFirst();
    }
    db.update(
        "insert into certificate_assets(material_id,owner_type,customer_id,scope_key,purpose,certificate_kind,chain_depth,issuer_material_id,issuer_asset_id,source_type,created_by) values(?,?,?,?,?,?,?,?,?,?,?)",
        m,
        owner,
        customer,
        scope,
        purpose,
        kind(c),
        index + 1,
        issuer,
        issuerAsset,
        source,
        actor);
    long id =
        db.queryForObject(
            "select id from certificate_assets where scope_key=? and material_id=?",
            Long.class,
            scope,
            m);
    for (int n = 0; n <= index; n++)
      db.update(
          "insert into asset_chain_nodes(asset_id,node_position,material_id) values(?,?,?)",
          id,
          n + 1,
          materialId(chain.get(n)));
    if (privateKey != null) {
      crypto.match(c, privateKey);
      var sealed = secrets.seal(privateKey.getEncoded());
      db.update(
          "insert into private_keys(own_asset_id,ciphertext,nonce,encryption_key_id,public_key_algorithm,spki_sha256) values(?,?,?,'master-v1',?,?)",
          id,
          sealed.ciphertext(),
          sealed.nonce(),
          privateKey.getAlgorithm(),
          crypto.fingerprint(c.getPublicKey().getEncoded()));
    }
    return id;
  }

  /**
   * @description 在同一事务中复用客户、保存完整链对应资产并记录导入审计
   * @author Freddy
   * @date 2026-11-12
   * @param p 已完成密码学解析与匹配校验的证书链及可选私钥
   * @param owner 归属类型 OWN 或 CUSTOMER
   * @param customerName 导入时的所属客户名称，自有证书可不填
   * @param purpose 证书用途 AS2、HTTPS 或 GENERAL
   * @param user 当前操作账号
   * @return 包含终端资产 ID 的响应
   */
  public synchronized Map<String, Object> saveImport(
      CertificateCrypto.Parsed p, String owner, String customerName, String purpose, String user) {
    require(Set.of("OWN", "CUSTOMER").contains(owner), "归属错误");
    require(Set.of("AS2", "HTTPS", "GENERAL").contains(purpose), "用途错误");
    require(owner.equals("OWN") || p.key() == null, "客户禁止私钥");
    return tx.execute(
        status -> {
          Long customer = null;
          if (owner.equals("CUSTOMER")) {
            customer = customers.findOrCreateForImport(customerName);
          }
          long actor = audit.actor(user), last = 0;
          for (int i = 0; i < p.chain().size(); i++) {
            if (owner.equals("CUSTOMER") && i < p.chain().size() - 1) continue;
            last =
                saveAsset(
                    p.chain(),
                    i,
                    owner,
                    customer,
                    purpose,
                    i == p.chain().size() - 1 ? p.key() : null,
                    "IMPORTED",
                    actor);
          }
          audit.record(user, "CERTIFICATE_IMPORT", "CERTIFICATE", last);
          return Map.of("id", last);
        });
  }

  /**
   * @description 校验签发层级与用途，在事务中保存新证书资产及审计
   * @author Freddy
   * @date 2026-11-12
   * @param b 现有接口约定的请求字段
   * @param user 当前操作账号
   * @return 证书签发结果
   */
  public synchronized Map<String, Object> issue(Map<String, Object> b, String user) {
    try {
      String k = Objects.toString(b.get("kind"), ""),
          cn = Objects.toString(b.get("cn"), ""),
          purpose = Objects.toString(b.get("purpose"), "GENERAL");
      require(Set.of("AS2", "HTTPS", "GENERAL").contains(purpose), "用途错误");
      int days = ((Number) b.getOrDefault("days", 365)).intValue();
      X509Certificate issuer = null;
      PrivateKey issuerKey = null;
      var chain = new ArrayList<X509Certificate>();
      if (!k.equals("ROOT")) {
        require(b.get("issuerId") instanceof Number, "必须选择上级证书");
        long id = ((Number) b.get("issuerId")).longValue();
        var row =
            db.queryForMap(
                "select * from certificate_assets where deleted_at is null and id=?", id);
        require("OWN".equals(row.get("OWNER_TYPE")), "必须选择自有CA");
        require(
            (k.equals("LEAF") ? "INTERMEDIATE" : "ROOT").equals(row.get("CERTIFICATE_KIND")),
            "上级证书层级不正确");
        for (var n :
            db.queryForList(
                "select material_id from asset_chain_nodes where asset_id=? order by node_position",
                Long.class,
                id)) {
          var c = material(n);
          c.checkValidity();
          chain.add(c);
        }
        issuer = material(((Number) row.get("MATERIAL_ID")).longValue());
        issuerKey = key(id);
      }
      @SuppressWarnings("unchecked")
      List<String> sans = (List<String>) b.getOrDefault("sans", List.of());
      var pair = crypto.issue(k, cn, days, issuer, issuerKey, sans, purpose, b);
      chain.add(pair.cert());
      return tx.execute(
          s -> {
            long id =
                saveAsset(
                    chain,
                    chain.size() - 1,
                    "OWN",
                    null,
                    purpose,
                    pair.key(),
                    "GENERATED",
                    audit.actor(user));
            audit.record(user, "CERTIFICATE_ISSUE", "CERTIFICATE", id);
            return Map.of("id", id);
          });
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("签发失败：检查上级证书和输入参数");
    }
  }

  private static final String SELECT =
      "select a.*,m.common_name,m.subject_dn,m.issuer_dn,m.serial_hex,m.not_before,m.not_after,m.fingerprint_sha256,m.public_key_algorithm,m.signature_algorithm,m.san_json,c.customer_name,im.common_name issuer_name,exists(select 1 from private_keys p where p.own_asset_id=a.id) has_key from certificate_assets a join certificate_materials m on m.id=a.material_id left join customers c on c.id=a.customer_id left join certificate_materials im on im.id=a.issuer_material_id ";

  /**
   * @description 将证书查询行转换为现有 HTTP 字段并计算当前有效状态
   * @author Freddy
   * @date 2026-11-12
   * @param r 证书联合查询结果行
   * @param n 查询结果的零基行序号
   * @return 符合现有响应字段的证书详情
   * @throws SQLException 查询行字段读取失败时抛出
   */
  private Map<String, Object> dto(ResultSet r, int n) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.getLong("id"));
    m.put("name", r.getString("common_name"));
    m.put("owner", r.getString("owner_type"));
    m.put("customer", r.getString("customer_name"));
    m.put("purpose", r.getString("purpose"));
    m.put("kind", r.getString("certificate_kind"));
    m.put("depth", r.getObject("chain_depth"));
    m.put("issuer", r.getString("issuer_name"));
    Instant start = r.getObject("not_before", OffsetDateTime.class).toInstant(),
        end = r.getObject("not_after", OffsetDateTime.class).toInstant(),
        now = Instant.now();
    int warning =
        db.queryForObject("select warning_days from notification_policy where id=1", Integer.class);
    m.put("notBefore", start.toString());
    m.put("notAfter", end.toString());
    m.put(
        "status",
        now.isBefore(start)
            ? "未生效"
            : !now.isBefore(end)
                ? "已过期"
                : now.plusSeconds(warning * 86400L).isAfter(end) ? "即将过期" : "有效");
    m.put("hasKey", r.getBoolean("has_key"));
    m.put("subject", r.getString("subject_dn"));
    m.put("issuerDn", r.getString("issuer_dn"));
    m.put("fingerprint", r.getString("fingerprint_sha256"));
    m.put("serial", r.getString("serial_hex"));
    m.put("algorithm", r.getString("public_key_algorithm"));
    m.put("signatureAlgorithm", r.getString("signature_algorithm"));
    m.put("san", r.getString("san_json"));
    return m;
  }

  /**
   * @description 按归属查询未删除证书，按到期时间和 ID 排序
   * @author Freddy
   * @date 2026-11-12
   * @param owner 归属类型 OWN 或 CUSTOMER
   * @return 证书列表，无记录时为空列表
   */
  public List<Map<String, Object>> list(String owner) {
    require(Set.of("OWN", "CUSTOMER").contains(owner), "归属错误");
    return db.query(
        SELECT + "where a.deleted_at is null and a.owner_type=? order by m.not_after,a.id",
        this::dto,
        owner);
  }

  /**
   * @description 读取未删除的证书资产，不存在时拒绝操作
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @return 证书详情
   */
  public Map<String, Object> detail(long id) {
    var rows = db.query(SELECT + "where a.deleted_at is null and a.id=?", this::dto, id);
    require(!rows.isEmpty(), "证书不存在");
    return rows.getFirst();
  }

  /**
   * @description 按根到终端顺序查询证书链节点及展示字段
   * @author Freddy
   * @date 2026-11-12
   * @param asset 证书资产 ID
   * @return 按根到终端排序的链节点
   */
  public List<Map<String, Object>> chain(long asset) {
    return db.query(
        "select n.node_position,m.* from asset_chain_nodes n join certificate_materials m on m.id=n.material_id where n.asset_id=? order by n.node_position",
        (r, n) -> {
          var m = new LinkedHashMap<String, Object>();
          m.put("name", r.getString("common_name"));
          m.put("depth", r.getInt("node_position"));
          m.put(
              "kind",
              r.getBoolean("is_ca")
                  ? (r.getInt("node_position") == 1 ? "ROOT" : "INTERMEDIATE")
                  : "LEAF");
          m.put("subject", r.getString("subject_dn"));
          m.put("issuer", r.getString("issuer_dn"));
          m.put("fingerprint", r.getString("fingerprint_sha256"));
          m.put(
              "notBefore", r.getObject("not_before", OffsetDateTime.class).toInstant().toString());
          m.put("serial", r.getString("serial_hex"));
          m.put("algorithm", r.getString("public_key_algorithm"));
          m.put("signatureAlgorithm", r.getString("signature_algorithm"));
          m.put("san", r.getString("san_json"));
          m.put("notAfter", r.getObject("not_after", OffsetDateTime.class).toInstant().toString());
          return m;
        },
        asset);
  }

  /**
   * @description 按根到终端顺序读取资产链的公钥证书
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @return 按根到终端排列的公钥证书
   */
  public List<X509Certificate> chainCertificates(long id) {
    return db
        .queryForList(
            "select material_id from asset_chain_nodes where asset_id=? order by node_position",
            Long.class,
            id)
        .stream()
        .map(this::material)
        .toList();
  }

  /**
   * @description 在同步锁和事务保护下校验证书引用，逻辑删除自有资产
   * @author Freddy
   * @date 2026-11-12
   * @param id 证书资产 ID
   * @param user 当前操作账号
   */
  public synchronized void deleteOwn(long id, String user) {
    tx.executeWithoutResult(
        status -> {
          db.queryForObject("select id from sync_guard where id=1 for update", Integer.class);
          var asset = detail(id);
          require("OWN".equals(asset.get("owner")), "仅支持删除自有证书");
          long material =
              db.queryForObject(
                  "select material_id from certificate_assets where id=?", Long.class, id);
          protectSyncReferences(id, material);
          var refs =
              db.queryForList(
                  "select distinct a.id,m.common_name from certificate_assets a join certificate_materials m on m.id=a.material_id where a.deleted_at is null and a.id<>? and (a.issuer_asset_id=? or a.issuer_material_id=? or exists(select 1 from asset_chain_nodes n where n.asset_id=a.id and n.material_id=?)) order by a.id",
                  id,
                  id,
                  material,
                  material);
          require(
              refs.isEmpty(),
              "证书被以下证书引用，无法删除："
                  + refs.stream()
                      .limit(5)
                      .map(r -> r.get("COMMON_NAME") + " (#" + r.get("ID") + ")")
                      .toList());
          require(
              db.queryForObject(
                      "select count(*) from notification_deliveries where asset_id=? and delivery_status='SENDING'",
                      Integer.class,
                      id)
                  == 0,
              "证书通知正在发送，请稍后重试");
          db.update("update certificate_assets set deleted_at=current_timestamp where id=?", id);
          audit.record(user, "CERTIFICATE_DELETE", "CERTIFICATE", id);
        });
  }

  /**
   * @description 检查任务、服务器信任和活跃执行对证书的引用
   * @author Freddy
   * @date 2026-11-12
   * @param asset 证书资产 ID
   * @param material 证书公钥材料 ID
   */
  private void protectSyncReferences(long asset, long material) {
    var tasks =
        db.queryForList(
            "select distinct t.name from sync_step_certificates c join sync_task_steps st on st.id=c.step_id join sync_tasks t on t.id=st.task_id where t.deleted_at is null and (c.source_asset_id=? or c.material_id=?)",
            String.class,
            asset,
            material);
    require(tasks.isEmpty(), "证书被同步任务引用：" + tasks);
    var servers =
        db.queryForList(
            "select distinct s.alias from managed_server_trust_certificates c join managed_servers s on s.current_config_id=c.server_config_id where s.deleted_at is null and (c.source_asset_id=? or c.material_id=?)",
            String.class,
            asset,
            material);
    require(servers.isEmpty(), "证书被HTTP服务器信任配置引用：" + servers);
    require(
        db.queryForObject(
                "select count(*) from sync_run_certificates c join sync_runs r on r.id=c.run_id where r.status in('QUEUED','RUNNING') and (c.source_asset_id=? or c.material_id=?)",
                Integer.class,
                asset,
                material)
            == 0,
        "证书被执行中的同步任务引用");
  }

  /**
   * @description 筛选指定下级类型可用的自有 CA，要求有效且包含私钥
   * @author Freddy
   * @date 2026-11-12
   * @param child 下级证书类型
   * @return 可用签发者列表
   */
  public List<Map<String, Object>> issuers(String child) {
    String kind = child.equals("LEAF") ? "INTERMEDIATE" : "ROOT";
    return list("OWN").stream()
        .filter(
            r ->
                kind.equals(r.get("kind"))
                    && Boolean.TRUE.equals(r.get("hasKey"))
                    && Set.of("有效", "即将过期").contains(r.get("status")))
        .toList();
  }
}
