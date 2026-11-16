package com.certmanage.sync;

import static com.certmanage.common.Errors.require;
import static com.certmanage.sync.SyncData.*;

import com.certmanage.certificate.CertificateService;
import com.certmanage.common.ConfigSecrets;
import com.certmanage.server.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * @description 校验和执行三个固定业务组件，文件内容始终在执行内存中流转
 * @author Freddy
 * @date 2026-11-13
 */
@Component
public class BusinessComponents {
  private final JdbcTemplate db;
  private final CertificateService certs;
  private final ServerService servers;
  private final ConfigSecrets secrets;
  private final RemoteTransport transport;
  private final ObjectMapper json;
  private static final int FILE_LIMIT = 10 * 1024 * 1024;

  /**
   * @description 注入业务组件使用的证书、连接、秘密及报文服务
   * @author Freddy
   * @date 2026-11-13
   * @param db 数据访问
   * @param certs 证书服务
   * @param servers 服务器服务
   * @param secrets 加密秘密
   * @param transport 远端调用
   * @param json JSON编码器
   */
  public BusinessComponents(
      JdbcTemplate db,
      CertificateService certs,
      ServerService servers,
      ConfigSecrets secrets,
      RemoteTransport transport,
      ObjectMapper json) {
    this.db = db;
    this.certs = certs;
    this.servers = servers;
    this.secrets = secrets;
    this.transport = transport;
    this.json = json;
  }

  /**
   * @description 按独立业务标识判断新组件，不改变旧HTTP组件含义
   * @author Freddy
   * @date 2026-11-13
   * @param c 组件配置
   * @return 是否声明业务标识
   */
  public static boolean matches(Map<String, Object> c) {
    return c.containsKey("businessComponent");
  }

  /**
   * @description 解析服务器路径和证书引用；自动私钥库按叶子CN选择到期最晚的有效链，别名留空采用CN
   * @author Freddy
   * @date 2026-11-13
   * @param step 含秘密版本的步骤
   * @param strict 是否按可执行任务校验
   * @throws Exception 证书编码或配置不合法
   */
  public void prepare(Map<String, Object> step, boolean strict) throws Exception {
    var c = map(step.get("config"));
    String kind = str(c, "businessComponent", "");
    require(Set.of("IS_TRUSTSTORE", "IS_KEYSTORE", "TN_CERTIFICATE").contains(kind), "业务组件类型错误");
    require("HTTP".equals(step.get("type")), "业务组件必须使用HTTP步骤载体");
    c.remove("resolvedBody");
    c.remove("resolvedEntries");
    c.remove("resolvedAssetId");
    var refs = new ArrayList<Map<String, Object>>();
    c.put("references", refs);
    c.put(
        "path",
        endpoint(
            c,
            "path",
            "serverId",
            kind.equals("TN_CERTIFICATE")
                ? "/restv2/wm.certificate:cert/tn/update"
                : "/restv2/wm.certificate:cert/keystore/update",
            "HTTP",
            strict));
    if (kind.equals("IS_TRUSTSTORE")) {
      String sourceType = str(c, "sourceType", "SFTP");
      require(Set.of("HTTP", "SFTP").contains(sourceType), "请选择SFTP或HTTP来源");
      c.put("sourceType", sourceType);
      String sourcePath = str(c, "sourcePath", "");
      if (strict || !sourcePath.isBlank()) validFilePath(sourcePath);
      c.put(
          "sourceUrl",
          endpoint(
              c,
              "sourceUrl",
              "sourceServerId",
              "/restv2/wm.certificate:cert/keystore/get",
              sourceType,
              strict));
      String method = str(c, "sourceMethod", "GET");
      require(Set.of("GET", "POST").contains(method), "读取方法只支持GET或POST");
      c.put("sourceMethod", method);
      c.put(
          "sourceHeaders",
          map(c.getOrDefault("sourceHeaders", Map.of("Content-Type", "application/json"))));
      c.put("sourceQuery", map(c.get("sourceQuery")));
    } else {
      for (String field :
          List.of(
              "sourceServerId",
              "sourceType",
              "sourceUrl",
              "sourceMethod",
              "sourceHeaders",
              "sourceQuery",
              "sourcePath")) c.remove(field);
    }
    if (kind.equals("IS_KEYSTORE")) {
      String format = str(c, "format", "PKCS12");
      require(Set.of("JKS", "PKCS12").contains(format), "请选择JKS或PKCS12格式");
      c.put("format", format);
      String path = str(c, "storePath", "");
      if (strict || !path.isBlank()) validFilePath(path);
    }
    if (!kind.equals("TN_CERTIFICATE")) {
      var secret = secrets.read(step.get("secretId") instanceof Number n ? n.longValue() : null);
      if (strict) {
        require(!str(secret, "password", "").isEmpty(), "请填写与原配置一致的证书库密码");
        if (kind.equals("IS_KEYSTORE"))
          require(!str(secret, "keyPassword", "").isEmpty(), "请填写私钥密码");
        require(!str(c, "keyStoreName", "").isBlank(), "请填写keyStoreName");
      }
      String name = str(c, "keyStoreName", "");
      require(
          name.length() <= 200 && name.codePoints().noneMatch(Character::isISOControl),
          "keyStoreName不合法");
    }
    String mode = str(c, "selectionMode", "AUTO");
    require(Set.of("AUTO", "MANUAL").contains(mode), "证书选择模式错误");
    c.put("selectionMode", mode);
    Long selected = null;
    if (kind.equals("IS_KEYSTORE")) {
      if (c.get("certificateId") != null) {
        selected = id(c, "certificateId");
        if (mode.equals("AUTO")) {
          // 基准只用于匹配CN，软删除后仍保留材料；候选必须满足正常可用条件。
          var names =
              db.queryForList(
                  "select m.common_name from certificate_assets a join certificate_materials m on m.id=a.material_id where a.id=? and a.owner_type='OWN' and a.certificate_kind='LEAF' and exists(select 1 from private_keys p where p.own_asset_id=a.id)",
                  String.class,
                  selected);
          require(names.size() == 1, "请选择含私钥的自有终端证书作为CN基准");
          String cn = Objects.toString(names.getFirst(), "");
          require(!cn.isBlank(), "自动选证要求基准叶子证书CN非空");
          c.put("certificateCn", cn);
          selected = null;
          var candidates =
              db.queryForList(
                  "select a.id from certificate_assets a join certificate_materials m on m.id=a.material_id where a.owner_type='OWN' and a.certificate_kind='LEAF' and m.common_name=? and a.deleted_at is null and a.archived_at is null and a.enabled=true and exists(select 1 from private_keys p where p.own_asset_id=a.id) order by m.not_after desc,a.id desc",
                  Long.class,
                  cn);
          for (long asset : candidates) {
            if (available(asset)) {
              selected = asset;
              break;
            }
          }
          if (strict) require(selected != null, "同CN的自有证书中没有含私钥且整链有效的可用证书");
        } else {
          var d = certs.detail(selected);
          require(
              "OWN".equals(d.get("owner"))
                  && "LEAF".equals(d.get("kind"))
                  && Boolean.TRUE.equals(d.get("hasKey")),
              "请选择含私钥的自有终端证书");
          c.remove("certificateCn");
        }
      } else if (strict) throw new IllegalArgumentException("请选择自有终端证书");
    } else {
      String purpose = str(c, "purpose", "GENERAL");
      require(Set.of("AS2", "HTTPS", "GENERAL").contains(purpose), "证书用途错误");
      c.put("purpose", purpose);
      if (c.get("customerId") != null) {
        long customer = id(c, "customerId");
        var rows =
            db.queryForList("select external_id,id_type from customers where id=?", customer);
        require(rows.size() == 1, "客户不存在");
        var candidates =
            db.queryForList(
                "select a.id from certificate_assets a join certificate_materials m on m.id=a.material_id where a.owner_type='CUSTOMER' and a.customer_id=? and a.purpose=? and a.deleted_at is null and a.archived_at is null and a.enabled=true order by m.not_after desc,a.id desc",
                Long.class,
                customer,
                purpose);
        if (mode.equals("MANUAL")) {
          if (c.get("certificateId") != null) {
            selected = id(c, "certificateId");
            require(candidates.contains(selected), "所选证书不属于当前客户用途或已停用归档");
          } else if (strict) throw new IllegalArgumentException("请选择证书链");
        } else
          for (long asset : candidates) {
            if (available(asset)) {
              selected = asset;
              break;
            }
          }
        if (strict) require(selected != null, "该客户用途下没有可用证书链");
        if (selected != null && kind.equals("TN_CERTIFICATE")) {
          String external = Objects.toString(rows.getFirst().get("EXTERNAL_ID"), ""),
              idType = Objects.toString(rows.getFirst().get("ID_TYPE"), "");
          if (strict) require(!external.isBlank() && !idType.isBlank(), "请补齐客户对外标识及标识类型");
          var chain = new ArrayList<Map<String, Object>>();
          int index = 1;
          for (var certificate : certs.chainCertificates(selected))
            chain.add(
                Map.of(
                    "index",
                    index++,
                    "certificateBase64",
                    Base64.getEncoder().encodeToString(certificate.getEncoded())));
          c.put(
              "resolvedBody",
              json.writeValueAsString(
                  Map.of("id", external, "idType", idType, "certChain", chain)));
        }
      } else if (strict) throw new IllegalArgumentException("请选择客户");
    }
    if (selected != null) {
      if (strict) {
        certs.requireEnabled(selected);
        require(available(selected), "所选证书链尚未生效或已过期");
      }
      c.put("resolvedAssetId", selected);
      var nodes =
          db.queryForList(
              "select material_id from asset_chain_nodes where asset_id=? order by node_position",
              Long.class,
              selected);
      Set<Long> requested = new HashSet<>();
      if (kind.equals("IS_TRUSTSTORE") && mode.equals("MANUAL") && c.containsKey("materialIds")) {
        require(c.get("materialIds") instanceof List<?>, "节点选择格式错误");
        for (Object value : (List<?>) c.get("materialIds")) {
          require(value instanceof Number, "节点标识错误");
          requested.add(((Number) value).longValue());
        }
        require(!requested.isEmpty() && nodes.containsAll(requested), "至少选择一个属于当前链的节点");
      }
      var entries = new ArrayList<Map<String, Object>>();
      for (long material : nodes) {
        if (kind.equals("IS_TRUSTSTORE") && !requested.isEmpty() && !requested.contains(material))
          continue;
        var ref =
            Map.<String, Object>of(
                "assetId",
                selected,
                "materialId",
                material,
                "alias",
                alias(material),
                "role",
                kind.equals("IS_KEYSTORE") ? "CHAIN" : "TRUST");
        refs.add(ref);
        entries.add(ref);
      }
      if (kind.equals("IS_KEYSTORE")) {
        String keyAlias = str(c, "alias", "");
        if (keyAlias.isBlank()) keyAlias = Objects.toString(certs.detail(selected).get("name"), "");
        require(!keyAlias.isBlank(), "证书CN为空，请填写keystore别名");
        var privateEntry = new LinkedHashMap<>(entries.getLast());
        privateEntry.put("alias", keyAlias);
        privateEntry.put("role", "PRIVATE");
        entries.clear();
        entries.add(privateEntry);
      }
      c.put("resolvedEntries", entries);
    }
    step.put("config", c);
  }

  /**
   * @description 规范接口URL，完整地址必须匹配所选服务器基础地址
   * @author Freddy
   * @date 2026-11-13
   * @param c 组件配置
   * @param field URL字段
   * @param serverField 服务器字段
   * @param fallback 默认路径
   * @param type 要求的服务器类型
   * @param strict 是否要求完整配置
   * @return 相对服务器路径
   */
  private String endpoint(
      Map<String, Object> c,
      String field,
      String serverField,
      String fallback,
      String type,
      boolean strict) {
    String path = str(c, field, fallback).trim();
    if (path.isEmpty()) path = fallback;
    if (c.get(serverField) != null) {
      long server = id(c, serverField);
      var snapshot = servers.snapshot(server);
      require(type.equals(snapshot.get("type")), "服务器类型与组件不匹配");
      if (strict) require(servers.enabled(server), "服务器已停用");
      if (type.equals("HTTP") && path.contains("://")) {
        URI url = URI.create(path),
            base =
                URI.create(servers.connection(id(snapshot, "configId")).get("baseUrl").toString());
        String prefix = Objects.toString(base.getRawPath(), "").replaceAll("/+$", "");
        require(
            Objects.equals(url.getScheme(), base.getScheme())
                && Objects.equals(url.getRawAuthority(), base.getRawAuthority())
                && url.getUserInfo() == null
                && url.getFragment() == null
                && url.getRawPath().startsWith(prefix + "/"),
            "URL必须属于所选服务器基础地址");
        path =
            url.getRawPath().substring(prefix.length())
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
      }
    } else if (strict) throw new IllegalArgumentException("请选择来源及目标服务器");
    require(
        path.startsWith("/")
            && !path.startsWith("//")
            && !path.contains("\\")
            && !path.contains("://")
            && !path.contains("#")
            && path.codePoints().noneMatch(Character::isISOControl),
        "接口URL不合法");
    require(!Arrays.asList(URI.create(path).getPath().split("/")).contains(".."), "接口路径不允许上级目录");
    return path;
  }

  /**
   * @description 校验远端文件完整路径
   * @author Freddy
   * @date 2026-11-13
   * @param path 文件全路径
   */
  private static void validFilePath(String path) {
    require(
        path.startsWith("/")
            && !path.endsWith("/")
            && !path.contains("\\")
            && path.codePoints().noneMatch(Character::isISOControl)
            && !Arrays.asList(path.split("/")).contains(".."),
        "请填写包含文件名的绝对路径");
  }

  /**
   * @description 判断整链是否处于有效期
   * @author Freddy
   * @date 2026-11-13
   * @param asset 终端资产
   * @return 整链当前可用
   */
  private boolean available(long asset) {
    try {
      var chain = certs.chainCertificates(asset);
      if (chain.isEmpty()) return false;
      for (var cert : chain) cert.checkValidity();
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  /**
   * @description 用CN与完整指纹生成稳定别名
   * @author Freddy
   * @date 2026-11-13
   * @param material 证书材料
   * @return 自动别名
   */
  private String alias(long material) {
    var row =
        db.queryForMap(
            "select common_name,fingerprint_sha256 from certificate_materials where id=?",
            material);
    String name =
        Objects.toString(row.get("COMMON_NAME"), "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-");
    if (name.length() > 100) name = name.substring(0, 100);
    name = name.replaceAll("^[._-]+|[._-]+$", "");
    return (name.isEmpty() ? "certificate" : name) + "-" + row.get("FINGERPRINT_SHA256");
  }

  /**
   * @description 按固定阶段读取原库、处理证书并调用更新接口，异常仅暴露安全阶段信息
   * @author Freddy
   * @date 2026-11-13
   * @param step 冻结步骤
   * @param allowance 当前任务剩余内存产物额度
   * @param progress 阶段日志接收器
   * @return 不含文件或请求正文的安全结果
   * @throws Exception 读取、处理或更新失败
   */
  public Map<String, Object> execute(
      Map<String, Object> step, long allowance, Consumer<String> progress) throws Exception {
    var c = map(step.get("config"));
    String kind = str(c, "businessComponent", ""), phase = "选择证书链";
    var phases = new ArrayList<String>();
    try {
      long used = 0;
      String body;
      if (kind.equals("TN_CERTIFICATE")) {
        progress.accept(phase + "成功");
        phases.add(phase + "成功");
        body = str(c, "resolvedBody", "");
      } else if (kind.equals("IS_KEYSTORE")) {
        phase = "创建keystore";
        progress.accept(phase + "开始");
        var secret = secrets.read(step.get("secretId") instanceof Number n ? n.longValue() : null);
        byte[] output = updateStore(kind, c, secret, null);
        used = output.length;
        require(used <= FILE_LIMIT && used <= allowance, "流程产物超出大小限制");
        String processing = phase + "成功：" + c.get("processedSummary");
        progress.accept(processing);
        phases.add(processing);
        body =
            json.writeValueAsString(
                Map.of(
                    "keyStoreName",
                    c.get("keyStoreName"),
                    "path",
                    c.get("storePath"),
                    "content",
                    Base64.getEncoder().encodeToString(output),
                    "type",
                    "key"));
      } else {
        phase = "读取来源";
        progress.accept(phase + "开始");
        var source = map(step.get("sourceServerSnapshot"));
        long server = id(source, "id"), version = id(source, "configId");
        int limit = (int) Math.min(FILE_LIMIT, allowance);
        RemoteTransport.RemoteFile file;
        if ("SFTP".equals(c.get("sourceType")))
          file =
              transport.readFile(
                  server,
                  version,
                  Map.of("operation", "READ", "sourcePath", c.get("sourcePath")),
                  limit);
        else {
          var query = map(c.get("sourceQuery"));
          query.put("path", c.get("sourcePath"));
          var request = new LinkedHashMap<String, Object>();
          request.put("path", c.get("sourceUrl"));
          request.put("method", c.get("sourceMethod"));
          request.put("bodyMode", "NONE");
          request.put("query", query);
          request.put("headers", c.get("sourceHeaders"));
          file = transport.readHttpFile(server, version, request, limit);
        }
        validFilePath(file.sourcePath());
        used += file.bytes().length;
        progress.accept(phase + "成功：" + file.sourcePath() + "，" + file.bytes().length + "字节");
        phases.add(phase + "成功");
        phase = "处理证书库";
        progress.accept(phase + "开始");
        var secret = secrets.read(step.get("secretId") instanceof Number n ? n.longValue() : null);
        byte[] output = updateStore(kind, c, secret, file.bytes());
        used += output.length;
        require(output.length <= FILE_LIMIT && used <= allowance, "流程产物超出大小限制");
        String processing = phase + "成功：" + c.get("processedSummary") + "，" + output.length + "字节";
        progress.accept(processing);
        phases.add(processing);
        body =
            json.writeValueAsString(
                Map.of(
                    "keyStoreName",
                    c.get("keyStoreName"),
                    "path",
                    file.sourcePath(),
                    "content",
                    Base64.getEncoder().encodeToString(output),
                    "type",
                    kind.equals("IS_TRUSTSTORE") ? "trust" : "key"));
      }
      phase = kind.equals("TN_CERTIFICATE") ? "更新TN证书" : "写入并重载";
      progress.accept(phase + "开始");
      var target = map(step.get("serverSnapshot"));
      var request =
          Map.<String, Object>of(
              "path",
              c.get("path"),
              "method",
              "POST",
              "bodyMode",
              "JSON",
              "httpComponent",
              "BUSINESS_UPDATE",
              "body",
              body);
      var result =
          new LinkedHashMap<>(
              transport.execute(id(target, "id"), id(target, "configId"), request, null, ""));
      progress.accept(phase + "成功");
      phases.add(phase + "成功");
      result.put("artifactBytes", used);
      result.put("phases", phases);
      return result;
    } catch (Exception failure) {
      com.certmanage.common.RawExecutionLog.exception(failure);
      progress.accept(phase + "失败");
      throw new RemoteFailure(
          "BUSINESS_COMPONENT_FAILED",
          phase,
          phase
              + "失败："
              + (failure instanceof RemoteFailure known
                  ? known.getMessage()
                  : "请检查该阶段配置、文件格式、密码和证书选择"));
    }
  }

  /**
   * @description 信任库保持来源格式原位更新，私钥库按指定格式新建并携带整链
   * @author Freddy
   * @date 2026-11-13
   * @param kind 业务类型
   * @param c 冻结配置
   * @param secret 密码配置
   * @param original 信任库原始字节；新建私钥库时为空
   * @return 新证书库字节
   * @throws Exception 密码、格式、别名或证书处理失败
   */
  private byte[] updateStore(
      String kind, Map<String, Object> c, Map<String, Object> secret, byte[] original)
      throws Exception {
    char[] password = str(secret, "password", "").toCharArray(),
        keyPassword = str(secret, "keyPassword", "").toCharArray();
    try {
      String format =
          original == null
              ? str(c, "format", "PKCS12")
              : original.length >= 4
                      && original[0] == (byte) 0xfe
                      && original[1] == (byte) 0xed
                      && original[2] == (byte) 0xfe
                      && original[3] == (byte) 0xed
                  ? "JKS"
                  : "PKCS12";
      var store = KeyStore.getInstance(format);
      store.load(original == null ? null : new ByteArrayInputStream(original), password);
      int deleted = 0;
      if (kind.equals("IS_TRUSTSTORE")) {
        for (String alias : Collections.list(store.aliases())) {
          require(store.isCertificateEntry(alias), "truststore来源包含私钥条目");
          var cert = (X509Certificate) store.getCertificate(alias);
          if (Boolean.TRUE.equals(c.get("deleteExpired"))
              && !cert.getNotAfter().toInstant().isAfter(Instant.now())) {
            store.deleteEntry(alias);
            deleted++;
          }
        }
        for (var entry : maps(c.get("resolvedEntries"))) {
          String alias = str(entry, "alias", "");
          var cert = certs.material(id(entry, "materialId"));
          require(
              !store.containsAlias(alias)
                  || Arrays.equals(store.getCertificate(alias).getEncoded(), cert.getEncoded()),
              "自动别名与已有不同证书冲突");
          store.setCertificateEntry(alias, cert);
        }
      } else {
        require(
            !format.equals("PKCS12") || Arrays.equals(password, keyPassword), "PKCS12私钥密码必须与库密码一致");
        store.load(null, password);
        long asset = id(c, "resolvedAssetId");
        var entries = maps(c.get("resolvedEntries"));
        store.setKeyEntry(
            str(entries.getLast(), "alias", ""),
            certs.key(asset),
            keyPassword,
            certs.chainCertificates(asset).reversed().toArray(X509Certificate[]::new));
      }
      c.put(
          "processedSummary",
          "格式"
              + format
              + "，删除"
              + deleted
              + "项，导入"
              + maps(c.get(kind.equals("IS_KEYSTORE") ? "references" : "resolvedEntries")).size()
              + "张证书");
      var output = new ByteArrayOutputStream();
      store.store(output, password);
      return output.toByteArray();
    } finally {
      Arrays.fill(password, '\0');
      Arrays.fill(keyPassword, '\0');
    }
  }
}
