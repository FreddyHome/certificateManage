package com.certmanage.server;

import com.certmanage.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * @description 管理服务器版本、秘密配置和证书信任引用，校验同步使用边界
 * @author Freddy
 * @date 2026-11-12
 */
@Service
public class ServerService {
  final JdbcTemplate db;
  final ConfigSecrets secrets;
  final ObjectMapper json;
  static final List<String> SECRET =
      List.of("password", "token", "privateKey", "passphrase", "secretHeaders");

  /**
   * @description 装配服务器持久化、秘密版本及JSON编码能力
   * @author Freddy
   * @date 2026-11-12
   * @param d 数据库访问模板
   * @param s 秘密版本存取组件
   * @param j JSON编码器
   */
  public ServerService(JdbcTemplate d, ConfigSecrets s, ObjectMapper j) {
    db = d;
    secrets = s;
    json = j;
  }

  /**
   * @description 读取未删除服务器原始记录，不存在时返回未找到异常
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   * @return 服务器持久化记录
   */
  Map<String, Object> row(long id) {
    var a = db.queryForList("select * from managed_servers where id=? and deleted_at is null", id);
    if (a.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return a.getFirst();
  }

  /**
   * @description 查询服务器是否允许执行远端操作
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   * @return 服务器启用时返回true
   */
  public boolean enabled(long id) {
    return Boolean.TRUE.equals(row(id).get("ENABLED"));
  }

  /**
   * @description 校验当前连接配置并生成服务器版本快照
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   * @return 服务器ID、配置ID、别名和协议快照
   */
  public Map<String, Object> snapshot(long id) {
    var r = row(id);
    requireReady(((Number) r.get("CURRENT_CONFIG_ID")).longValue());
    return Map.of(
        "id",
        id,
        "configId",
        r.get("CURRENT_CONFIG_ID"),
        "alias",
        r.get("ALIAS"),
        "type",
        r.get("TYPE"));
  }

  /**
   * @description 按配置版本读取公开配置和解密凭据，仅供内部连接使用
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   * @return 内部连接配置，包含解密凭据
   */
  public Map<String, Object> connection(long id) {
    return db.queryForObject(
        "select public_config_json,secret_bundle_id from managed_server_configs where id=?",
        (r, n) -> {
          try {
            Map<String, Object> m =
                json.readValue(
                    r.getString(1),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            m.putAll(secrets.read(r.getObject(2, Long.class)));
            m.put(
                "_trustMaterialIds",
                db.queryForList(
                    "select material_id from managed_server_trust_certificates where server_config_id=?",
                    Long.class,
                    id));
            return m;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        id);
  }

  /**
   * @description 读取服务器编辑详情，移除凭据明文并返回已配置标记
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   * @return 去除秘密明文的管理响应
   */
  public Map<String, Object> get(long id) {
    var r = row(id);
    var c = connection(((Number) r.get("CURRENT_CONFIG_ID")).longValue());
    c.remove("_trustMaterialIds");
    for (String s : SECRET) {
      Object v = c.remove(s);
      c.put(s + "Configured", v != null && !v.toString().isBlank());
    }
    return Map.of(
        "id",
        id,
        "alias",
        r.get("ALIAS"),
        "type",
        r.get("TYPE"),
        "description",
        Objects.toString(r.get("DESCRIPTION"), ""),
        "enabled",
        r.get("ENABLED"),
        "revision",
        r.get("CONFIG_REVISION"),
        "config",
        c);
  }

  /**
   * @description 按服务器ID顺序返回去除秘密的管理列表
   * @author Freddy
   * @date 2026-11-12
   * @return 服务器管理列表
   */
  public List<Map<String, Object>> list() {
    return db
        .queryForList("select id from managed_servers where deleted_at is null order by id")
        .stream()
        .map(r -> get(((Number) r.get("ID")).longValue()))
        .toList();
  }

  /**
   * @description 取得同步全局事务锁，保护配置和引用关系
   * @author Freddy
   * @date 2026-11-12
   */
  void guard() {
    db.queryForObject("select id from sync_guard where id=1 for update", Integer.class);
  }

  /**
   * @description 事务内校验并保存服务器版本，仅允许引用启用的客户信任证书
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   * @param input 服务器配置请求，编辑时必须提供当前版本
   * @return 保存后的管理响应
   */
  @Transactional
  public Map<String, Object> save(Long id, Map<String, Object> input) {
    guard();
    String alias = Objects.toString(input.get("alias"), "").trim(),
        type = Objects.toString(input.get("type"), "");
    Errors.require(!alias.isBlank() && alias.length() <= 200, "别名不合法");
    Errors.require(List.of("HTTP", "SFTP", "SSH").contains(type), "协议不合法");
    long rev = 1;
    Map<String, Object> previous = new LinkedHashMap<>();
    if (id != null) {
      var r = row(id);
      rev = ((Number) r.get("CONFIG_REVISION")).longValue() + 1;
      if (!Objects.toString(input.getOrDefault("revision", input.get("configRevision")))
          .equals(String.valueOf(rev - 1)))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已修改");
      if (!type.equals(r.get("TYPE")))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "不可变更协议");
      previous = connection(((Number) r.get("CURRENT_CONFIG_ID")).longValue());
    } else {
      db.update(
          "insert into managed_servers(alias,alias_key,type,description,enabled,config_revision) values(?,?,?,?,?,1)",
          alias,
          alias.toLowerCase(Locale.ROOT),
          type,
          Objects.toString(input.get("description"), ""),
          input.getOrDefault("enabled", true));
      id =
          db.queryForObject(
              "select id from managed_servers where alias_key=?",
              Long.class,
              alias.toLowerCase(Locale.ROOT));
    }
    Map<String, Object> c =
        new LinkedHashMap<>((Map<String, Object>) input.getOrDefault("config", Map.of()));
    for (String s : SECRET) {
      Object v = c.get(s);
      if (v == null || v.toString().isBlank())
        if (previous.containsKey(s)) c.put(s, previous.get(s));
      c.remove(s + "Configured");
    }
    for (Object clear : (Collection<?>) c.getOrDefault("clearSecrets", List.of())) {
      Errors.require(SECRET.contains(clear.toString()), "清除字段不合法");
      c.remove(clear.toString());
    }
    c.remove("clearSecrets");
    c.put("type", type);
    validate(c);
    Map<String, Object> secret = new LinkedHashMap<>();
    for (String s : SECRET) if (c.containsKey(s)) secret.put(s, c.remove(s));
    long bundle = secrets.save(secret);
    try {
      db.update(
          "insert into managed_server_configs(server_id,revision,public_config_json,secret_bundle_id) values(?,?,?,?)",
          id,
          rev,
          json.writeValueAsString(c),
          bundle);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    long cid =
        db.queryForObject(
            "select id from managed_server_configs where server_id=? and revision=?",
            Long.class,
            id,
            rev);
    for (Object asset : (Collection<?>) c.getOrDefault("trustAssetIds", List.of())) {
      long aid = Long.parseLong(asset.toString());
      var materials =
          db.queryForList(
              "select n.material_id from asset_chain_nodes n join certificate_assets a on a.id=n.asset_id join certificate_materials m on m.id=n.material_id where a.id=? and a.deleted_at is null and a.archived_at is null and a.enabled=true and a.owner_type='CUSTOMER'",
              aid);
      Errors.require(!materials.isEmpty(), "信任证书来源不合法、已归档或已禁用");
      for (var material : materials) {
        long mid = ((Number) material.get("MATERIAL_ID")).longValue();
        byte[] der =
            db.queryForObject(
                "select certificate_der from certificate_materials where id=?", byte[].class, mid);
        try {
          var cert =
              (java.security.cert.X509Certificate)
                  java.security.cert.CertificateFactory.getInstance("X.509")
                      .generateCertificate(new java.io.ByteArrayInputStream(der));
          if (cert.getBasicConstraints() >= 0)
            db.update("insert into managed_server_trust_certificates values(?,?,?)", cid, aid, mid);
        } catch (java.security.cert.CertificateException e) {
          throw new IllegalArgumentException("信任证书不合法");
        }
      }
    }
    db.update(
        "update managed_servers set alias=?,alias_key=?,description=?,enabled=?,config_revision=?,current_config_id=?,updated_at=current_timestamp where id=?",
        alias,
        alias.toLowerCase(Locale.ROOT),
        Objects.toString(input.get("description"), ""),
        input.getOrDefault("enabled", true),
        rev,
        cid,
        id);
    return get(id);
  }

  /**
   * @description 校验协议地址、认证和超时等连接字段
   * @author Freddy
   * @date 2026-11-12
   * @param c 待校验的连接配置
   */
  static void validate(Map<String, Object> c) {
    if ("HTTP".equals(c.get("type"))) {
      var u = java.net.URI.create(Objects.toString(c.get("baseUrl"), ""));
      Errors.require(
          Set.of("http", "https").contains(Objects.toString(u.getScheme(), ""))
              && u.getHost() != null
              && u.getUserInfo() == null
              && u.getQuery() == null
              && u.getFragment() == null,
          "基础地址不合法");
      RemoteTransport.safePath(Objects.toString(u.getRawPath(), ""));
      Errors.require(
          List.of("NONE", "BASIC", "BEARER").contains(c.getOrDefault("authType", "NONE")),
          "认证方式不合法");
    } else {
      Errors.require(
          List.of("PASSWORD", "KEY").contains(c.getOrDefault("authType", "PASSWORD")),
          "SSH/SFTP认证方式不合法");
      int port = Integer.parseInt(c.getOrDefault("port", 22).toString());
      Errors.require(port >= 1 && port <= 65535, "SSH/SFTP端口不合法");
      Errors.require(
          !Objects.toString(c.get("host"), "").isBlank()
              && !Objects.toString(c.get("username"), "").isBlank(),
          "主机和用户必填");
      c.remove("fingerprint");
    }
    for (String t : List.of("connectTimeout", "readTimeout")) {
      int v = Integer.parseInt(c.getOrDefault(t, t.equals("connectTimeout") ? 10 : 30).toString());
      Errors.require(v >= 1 && v <= 300, "超时须为1至300秒");
      c.put(t, v);
    }
  }

  /**
   * @description 检查同步任务和活跃执行引用后逻辑删除服务器
   * @author Freddy
   * @date 2026-11-12
   * @param id 服务器或配置版本ID，以方法职责为准
   */
  @Transactional
  public void delete(long id) {
    guard();
    row(id);
    int count =
        db.queryForObject(
            "select count(*) from sync_step_servers s join sync_task_steps st on st.id=s.step_id join sync_tasks t on t.id=st.task_id where s.server_id=? and t.deleted_at is null",
            Integer.class,
            id);
    count +=
        db.queryForObject(
            "select count(*) from sync_run_servers s join sync_runs r on r.id=s.run_id where s.server_id=? and r.status in ('QUEUED','RUNNING')",
            Integer.class,
            id);
    if (count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "服务器被任务引用");
    db.update(
        "update managed_servers set deleted_at=current_timestamp,enabled=false where id=?", id);
  }

  /**
   * @description 校验指定配置所需认证字段是否完整
   * @author Freddy
   * @date 2026-11-12
   * @param configId 服务器配置版本ID
   */
  public void requireReady(long configId) {
    var c = connection(configId);
    String auth = Objects.toString(c.get("authType"), "NONE");
    if (Set.of("SFTP", "SSH").contains(c.get("type"))) {
      Errors.require(!Objects.toString(c.get("username"), "").isBlank(), "缺少用户名");
      Errors.require(
          !Objects.toString(c.get(auth.equals("KEY") ? "privateKey" : "password"), "").isBlank(),
          "缺少SSH/SFTP凭据");
    } else if (auth.equals("BEARER"))
      Errors.require(!Objects.toString(c.get("token"), "").isBlank(), "缺少令牌");
    else if (auth.equals("BASIC"))
      Errors.require(
          !Objects.toString(c.get("username"), "").isBlank()
              && !Objects.toString(c.get("password"), "").isBlank(),
          "缺少HTTP凭据");
  }
}
