package com.certmanage.sync;

import static com.certmanage.common.Errors.require;
import static com.certmanage.sync.SyncData.*;

import com.certmanage.certificate.CertificateService;
import com.certmanage.common.ConfigSecrets;
import com.certmanage.server.ServerService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
/**
 * @description 校验流程配置并解析执行所需的证书引用
 * @author Freddy
 * @date 2026-11-12
 */
public class WorkflowValidator {
  private final FixedHttpComponents fixed;
  private final JdbcTemplate db;
  private final CertificateService certs;
  private final ServerService servers;
  private final ConfigSecrets secrets;

  /**
   * @description 校验流程配置并解析执行所需的证书引用
   * @author Freddy
   * @date 2026-11-12
   * @param db 操作所需的配置或依赖
   * @param certs 操作所需的配置或依赖
   * @param servers 操作所需的配置或依赖
   * @param secrets 操作所需的配置或依赖
   * @param fixed 操作所需的配置或依赖
   */
  public WorkflowValidator(
      JdbcTemplate db,
      CertificateService certs,
      ServerService servers,
      ConfigSecrets secrets,
      FixedHttpComponents fixed) {
    this.fixed = fixed;
    this.db = db;
    this.certs = certs;
    this.servers = servers;
    this.secrets = secrets;
  }

  /**
   * @description 计算Cron与时区对应的后续执行时间
   * @author Freddy
   * @date 2026-11-12
   * @param cron 操作所需的配置或依赖
   * @param zone 操作所需的配置或依赖
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  public List<String> nextTimes(String cron, String zone) {
    try {
      var expression = CronExpression.parse(cron);
      var now = ZonedDateTime.now(ZoneId.of(zone));
      var result = new ArrayList<String>();
      for (int i = 0; i < 3; i++) {
        now = expression.next(now);
        require(now != null, "Cron没有未来执行时间");
        result.add(now.toString());
      }
      return result;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Cron或时区不合法：使用六字段Cron和有效时区");
    }
  }

  /**
   * @description 校验步骤配置并解析执行引用
   * @author Freddy
   * @date 2026-11-12
   * @param steps 操作所需的配置或依赖
   * @param strict 是否执行严格校验
   */
  public void prepare(List<Map<String, Object>> steps, boolean strict) {
    require(steps.size() <= 30, "最多30个流程步骤");
    if (strict) require(!steps.isEmpty(), "流程至少包含一个步骤");
    var earlier = new HashSet<String>();
    var all = new HashSet<String>();
    boolean transfer = false;
    for (var step : steps) {
      String key = str(step, "key", "");
      require(key.matches("[A-Za-z0-9_-]{1,100}") && all.add(key), "步骤标识重复或不合法");
      String type = str(step, "type", "");
      require(Set.of("KEYSTORE", "TRUSTSTORE", "SFTP", "HTTP").contains(type), "组件类型错误");
      String name = str(step, "name", type).trim();
      require(!name.isEmpty() && name.length() <= 200, "步骤名称长度1-200");
      step.put("name", name);
      var c = map(step.get("config"));
      step.put("config", c);
      c.remove("resolvedEntries");
      c.remove("references");
      var refs = new ArrayList<Map<String, Object>>();
      c.put("references", refs);
      if (type.equals("KEYSTORE") || type.equals("TRUSTSTORE")) {
        String format = str(c, "format", "PKCS12");
        require(Set.of("JKS", "PKCS12").contains(format), "Store格式必须为JKS或PKCS12");
        c.put("format", format);
        filename(c, format.equals("JKS") ? "store.jks" : "store.p12");
        var secret =
            secrets.read(
                step.get("secretId") == null ? null : ((Number) step.get("secretId")).longValue());
        if (strict) {
          require(str(secret, "password", "").length() >= 8, "Store密码至少8位");
          String keyPassword = str(secret, "keyPassword", str(secret, "password", ""));
          require(keyPassword.length() >= 8, "Key密码至少8位");
          if (format.equals("PKCS12"))
            require(keyPassword.equals(secret.get("password")), "PKCS12的Key密码必须与Store密码相同");
        }
        var resolved = new ArrayList<Map<String, Object>>();
        var aliases = new HashSet<String>();
        var trustMaterials = new HashSet<Long>();
        int privateCount = 0;
        var entries = maps(c.get("entries"));
        require(entries.size() <= 100, "每个Store最多100个输入条目");
        for (var entry : entries) {
          long asset = id(entry, "assetId");
          String alias = str(entry, "alias", "").trim();
          require(
              !alias.isEmpty()
                  && alias.length() <= 180
                  && !alias.codePoints().anyMatch(Character::isISOControl),
              "条目别名必填且不超过180字");
          String role = type.equals("TRUSTSTORE") ? "TRUST" : str(entry, "role", "PRIVATE");
          require(Set.of("PRIVATE", "TRUST").contains(role), "条目类型错误");
          var detail = certs.detail(asset);
          var chain = materialNodes(asset);
          long terminal = ((Number) chain.getLast().get("materialId")).longValue();
          if (role.equals("PRIVATE")) {
            require(
                "OWN".equals(detail.get("owner")) && Boolean.TRUE.equals(detail.get("hasKey")),
                "私钥条目只能选择含私钥的自有证书");
            add(resolved, aliases, asset, terminal, alias, "PRIVATE");
            privateCount++;
            for (var node : chain) {
              long material = ((Number) node.get("materialId")).longValue();
              refs.add(ref(asset, material, alias + "-chain-" + node.get("position"), "CHAIN"));
              if (strict) valid(material);
            }
          } else {
            var nodes =
                Boolean.TRUE.equals(entry.get("includeChain")) ? chain : List.of(chain.getLast());
            for (var node : nodes) {
              long material = ((Number) node.get("materialId")).longValue();
              if (!trustMaterials.add(material)) continue;
              String nodeAlias =
                  material == terminal ? alias : alias + "-ca-" + node.get("position");
              add(resolved, aliases, asset, material, nodeAlias, "TRUST");
              refs.add(ref(asset, material, nodeAlias, "TRUST"));
              if (strict) valid(material);
            }
          }
        }
        if (strict) {
          require(!resolved.isEmpty(), "请选择Store证书");
          require(!type.equals("KEYSTORE") || privateCount > 0, "Keystore至少包含一个私钥条目");
        }
        c.put("resolvedEntries", resolved);
        earlier.add(key);
      } else {
        transfer = true;
        if (type.equals("HTTP")) fixed.prepare(c, refs, strict);
        String source = str(c, "sourceStep", "");
        if (!source.isBlank()) require(earlier.contains(source), "产物来源必须是前序制作步骤");
        if (c.get("serverId") != null) {
          var server = servers.snapshot(id(c, "serverId"));
          require(type.equals(server.get("type")), "上传组件与服务器类型不匹配");
          if (strict) require(servers.enabled(id(c, "serverId")), "服务器已停用");
        } else if (strict) throw new IllegalArgumentException("请选择服务器");
        if (c.get("certificateId") != null) {
          require(source.isBlank(), "文件输入只能选择产物或证书其中一种");
          long asset = id(c, "certificateId");
          certs.detail(asset);
          String format = str(c, "exportFormat", "PEM");
          require(Set.of("PEM", "CHAIN_PEM", "DER", "P7B").contains(format), "公钥导出格式错误");
          c.put("exportFormat", format);
          var nodes = materialNodes(asset);
          if (format.equals("PEM") || format.equals("DER")) nodes = List.of(nodes.getLast());
          for (var node : nodes) {
            long material = ((Number) node.get("materialId")).longValue();
            refs.add(ref(asset, material, "input-" + node.get("position"), "PUBLIC"));
            if (strict) valid(material);
          }
        }
        boolean file = !source.isBlank() || c.get("certificateId") != null;
        filename(c, "certificate.pem");
        if (type.equals("SFTP")) {
          String operation = str(c, "operation", "UPLOAD");
          require(Set.of("UPLOAD", "MOVE").contains(operation), "SFTP操作类型错误");
          if (operation.equals("MOVE")) {
            require(
                str(c, "sourceStep", "").isBlank() && c.get("certificateId") == null,
                "移动操作不需要文件输入");
            if (strict) {
              for (String field : List.of("sourcePath", "targetPath")) {
                String path = str(c, field, "");
                require(
                    path.startsWith("/")
                        && !path.endsWith("/")
                        && !path.contains("\\")
                        && !path.codePoints().anyMatch(Character::isISOControl)
                        && !Arrays.asList(path.split("/")).contains(".."),
                    "请填写有效的源和目标绝对文件路径");
              }
              require(
                  Boolean.TRUE.equals(c.get("appendTimestamp"))
                      || !c.get("sourcePath").equals(c.get("targetPath")),
                  "源路径和目标路径不能相同");
            }
          } else if (strict) {
            require(file, "SFTP上传必须选择输入文件");
            String directory = str(c, "remoteDirectory", "");
            require(
                !directory.isBlank()
                    && !Arrays.asList(directory.split("/")).contains("..")
                    && !directory.contains("\\")
                    && !directory.contains("\0"),
                "远程目录不合法");
          }
        } else {
          String method = str(c, "method", "POST"), mode = str(c, "bodyMode", "MULTIPART");
          require(
              Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method),
              "HTTP请求方法不合法");
          require(
              Set.of("NONE", "FORM", "TEXT", "MULTIPART", "BINARY", "JSON").contains(mode),
              "HTTP请求模式错误");
          if (strict)
            require(!Set.of("MULTIPART", "BINARY").contains(mode) || file, "HTTP文件上传必须选择输入文件");
          require(
              !Set.of("GET", "HEAD").contains(method) || mode.equals("NONE"), "GET/HEAD请求请选择无请求体");
          String path = str(c, "path", "/");
          require(
              path.startsWith("/")
                  && !path.startsWith("//")
                  && !path.contains("://")
                  && !path.contains("\\"),
              "接口路径必须为相对服务器路径");
          require(str(c, "body", "").length() <= 100000, "JSON模板过长");
        }
      }
    }
    if (strict) require(transfer, "同步流程至少包含一个上传或HTTP调用组件");
  }

  /**
   * @description 校验并设置安全文件名
   * @author Freddy
   * @date 2026-11-12
   * @param c 组件配置
   * @param fallback 操作所需的配置或依赖
   */
  private void filename(Map<String, Object> c, String fallback) {
    String name = str(c, "filename", fallback).trim();
    require(
        !name.isEmpty()
            && name.length() <= 200
            && !name.contains("/")
            && !name.contains("\\")
            && !name.equals(".")
            && !name.equals("..")
            && !name.codePoints().anyMatch(Character::isISOControl),
        "文件名不合法");
    c.put("filename", name);
  }

  /**
   * @description 检查别名唯一性并增加证书条目
   * @author Freddy
   * @date 2026-11-12
   * @param entries 操作所需的配置或依赖
   * @param aliases 操作所需的配置或依赖
   * @param asset 操作所需的配置或依赖
   * @param material 证书材料标识
   * @param alias 操作所需的配置或依赖
   * @param role 操作所需的配置或依赖
   */
  private void add(
      List<Map<String, Object>> entries,
      Set<String> aliases,
      long asset,
      long material,
      String alias,
      String role) {
    require(aliases.add(alias.toLowerCase(Locale.ROOT)), "Store条目别名重复：" + alias);
    entries.add(ref(asset, material, alias, role));
  }

  /**
   * @description 生成冻结证书引用
   * @author Freddy
   * @date 2026-11-12
   * @param asset 操作所需的配置或依赖
   * @param material 证书材料标识
   * @param alias 操作所需的配置或依赖
   * @param role 操作所需的配置或依赖
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  private Map<String, Object> ref(long asset, long material, String alias, String role) {
    return Map.of("assetId", asset, "materialId", material, "alias", alias, "role", role);
  }

  /**
   * @description 查询根到终端排列的材料节点
   * @author Freddy
   * @date 2026-11-12
   * @param asset 操作所需的配置或依赖
   * @return 无效配置时抛出异常，否则返回处理结果
   */
  private List<Map<String, Object>> materialNodes(long asset) {
    return db.query(
        "select node_position,material_id from asset_chain_nodes where asset_id=? order by node_position",
        (r, n) -> Map.<String, Object>of("materialId", r.getLong(2), "position", r.getInt(1)),
        asset);
  }

  /**
   * @description 验证材料证书当前有效期
   * @author Freddy
   * @date 2026-11-12
   * @param material 证书材料标识
   */
  private void valid(long material) {
    try {
      certs.material(material).checkValidity();
    } catch (Exception e) {
      throw new IllegalArgumentException("证书尚未生效或已过期，无法执行");
    }
  }
}
