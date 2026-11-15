package com.certmanage.sync;

import static com.certmanage.common.Errors.require;
import static com.certmanage.sync.SyncData.*;

import com.certmanage.certificate.*;
import com.certmanage.common.ConfigSecrets;
import com.certmanage.server.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
/**
 * @description 执行同步快照并记录步骤状态和业务结果
 * @author Freddy
 * @date 2026-11-12
 */
public class WorkflowRunner {
  private final JdbcTemplate db;
  private final BusinessComponents business;
  private final com.certmanage.common.RawExecutionLog raw;
  private final TransactionTemplate tx;
  private final WorkflowService workflows;
  private final StoreMaker stores;
  private final ConfigSecrets secrets;
  private final CertificateService certs;
  private final CertificateCrypto crypto;
  private final RemoteTransport transport;
  private final ServerService servers;
  private final com.certmanage.common.ExecutionLog logs;
  private final Map<Long, String> active = new ConcurrentHashMap<>();

  /**
   * @description 执行同步快照并记录步骤状态和业务结果
   * @author Freddy
   * @date 2026-11-12
   * @param db 操作所需的配置或依赖
   * @param tx 操作所需的配置或依赖
   * @param workflows 操作所需的配置或依赖
   * @param stores 操作所需的配置或依赖
   * @param secrets 操作所需的配置或依赖
   * @param certs 操作所需的配置或依赖
   * @param crypto 操作所需的配置或依赖
   * @param transport 操作所需的配置或依赖
   * @param servers 操作所需的配置或依赖
   * @param logs 操作所需的配置或依赖
   * @param business 固定业务组件执行服务
   * @param raw 加密原始日志服务
   */
  public WorkflowRunner(
      JdbcTemplate db,
      TransactionTemplate tx,
      WorkflowService workflows,
      StoreMaker stores,
      ConfigSecrets secrets,
      CertificateService certs,
      CertificateCrypto crypto,
      RemoteTransport transport,
      ServerService servers,
      com.certmanage.common.ExecutionLog logs,
      BusinessComponents business,
      com.certmanage.common.RawExecutionLog raw) {
    this.raw = raw;
    this.business = business;
    this.db = db;
    this.tx = tx;
    this.workflows = workflows;
    this.stores = stores;
    this.secrets = secrets;
    this.certs = certs;
    this.crypto = crypto;
    this.transport = transport;
    this.servers = servers;
    this.logs = logs;
  }

  /**
   * @description 领取最早排队任务并执行
   * @author Freddy
   * @date 2026-11-12
   */
  public void runNext() {
    var ids =
        db.queryForList(
            "select id from sync_runs where status='QUEUED' order by id fetch first 1 row only",
            Long.class);
    if (!ids.isEmpty()) executeRun(ids.getFirst());
  }

  /**
   * @description 执行冻结快照，将制作和 SFTP 读取产物供后续步骤复用，仅记录文件元数据
   * @author Freddy
   * @date 2026-11-12
   * @param id 记录标识
   */
  public void executeRun(long id) {
    String owner = UUID.randomUUID().toString();
    Boolean claimed =
        tx.execute(
            s -> {
              workflows.lock();
              return db.update(
                      "update sync_runs set status='RUNNING',started_at=current_timestamp,lease_owner=?,lease_until=dateadd('MINUTE',5,current_timestamp) where id=? and status='QUEUED'",
                      owner,
                      id)
                  == 1;
            });
    if (!Boolean.TRUE.equals(claimed)) return;
    active.put(id, owner);
    String current = null;
    String currentDescription = "";
    String taskName = "";
    String actor = "system";
    var files = new HashMap<String, Artifact>();
    long totalBytes = 0;
    var rawScope = raw.begin();
    try {
      var snapshot =
          workflows.decode(
              db.queryForObject(
                  "select snapshot_json from sync_runs where id=?", String.class, id));
      actor = str(snapshot, "requestedBy", "system");
      taskName = str(snapshot, "name", "同步任务");
      logs.append(
          "SYNC",
          "RUN_STARTED",
          "STARTED",
          actor,
          id,
          null,
          "SYNC_RUN",
          id,
          "同步任务“" + taskName + "”开始执行",
          Map.of());
      for (var step : maps(snapshot.get("steps"))) {
        current = str(step, "key", "");
        com.certmanage.common.RawExecutionLog.step(current);
        String type = str(step, "type", "");
        currentDescription =
            (switch (type) {
                  case "KEYSTORE" -> "制作Keystore";
                  case "TRUSTSTORE" -> "制作Truststore";
                  case "SSH" -> "Shell命令执行";
                  case "SFTP" ->
                      switch (str(map(step.get("config")), "operation", "UPLOAD")) {
                        case "MOVE" -> "SFTP移动/重命名";
                        case "READ" -> "SFTP读取";
                        default -> "SFTP上传";
                      };
                  default -> "HTTP接口调用";
                })
                + "步骤“"
                + str(step, "name", type)
                + "”";
        var c = map(step.get("config"));
        db.update(
            "update sync_run_steps set status='RUNNING',started_at=current_timestamp where run_id=? and step_key=?",
            id,
            current);
        logs.append(
            "SYNC",
            "STEP_STARTED",
            "STARTED",
            actor,
            id,
            current,
            "SYNC_RUN",
            id,
            currentDescription + "开始",
            Map.of());
        heartbeat();
        // 同时校验输入证书与服务器信任证书，已排队任务不绕过后续的禁用操作。
        for (long referencedAsset :
            db.queryForList(
                "select distinct source_asset_id from sync_run_certificates where run_id=?",
                Long.class,
                id)) {
          certs.requireEnabled(referencedAsset);
        }

        for (var ref : maps(c.get("references"))) {
          certs.requireEnabled(id(ref, "assetId"));
          certs.material(id(ref, "materialId")).checkValidity();
        }
        Map<String, Object> result;
        if (type.equals("SSH")) {
          var server = map(step.get("serverSnapshot"));
          long serverId = id(server, "id");
          require(servers.enabled(serverId), "目标服务器已停用");
          logs.append(
              "SYNC",
              "SSH_STARTED",
              "STARTED",
              actor,
              id,
              current,
              "SERVER",
              serverId,
              "开始在服务器“" + server.get("alias") + "”执行Shell命令",
              Map.of("serverAlias", server.get("alias")));
          result = transport.execute(serverId, id(server, "configId"), c, null, null);
        } else if (BusinessComponents.matches(c)) {
          String stepKey = current;
          String runActor = actor;
          result =
              business.execute(
                  step,
                  25 * 1024 * 1024 - totalBytes,
                  message -> {
                    heartbeat();
                    logs.append(
                        "SYNC",
                        "COMPONENT_PHASE",
                        message.contains("失败")
                            ? "FAILURE"
                            : message.contains("成功") ? "SUCCESS" : "STARTED",
                        runActor,
                        id,
                        stepKey,
                        "SYNC_RUN",
                        id,
                        message,
                        Map.of());
                  });
          totalBytes += ((Number) result.getOrDefault("artifactBytes", 0)).longValue();
        } else if (type.equals("KEYSTORE") || type.equals("TRUSTSTORE")) {
          Long secretId =
              step.get("secretId") == null ? null : ((Number) step.get("secretId")).longValue();
          byte[] data = stores.make(type, c, secrets.read(secretId));
          totalBytes += data.length;
          require(
              data.length <= 10 * 1024 * 1024 && totalBytes <= 25 * 1024 * 1024, "流程产物总大小超过25MB限制");
          files.put(current, new Artifact(data, str(c, "filename", "store.p12")));
          result =
              Map.of(
                  "filename",
                  str(c, "filename", "store.p12"),
                  "bytes",
                  data.length,
                  "entries",
                  maps(c.get("resolvedEntries")).size());
        } else {
          var server = map(step.get("serverSnapshot"));
          long serverId = id(server, "id");
          require(servers.enabled(serverId), "目标服务器已停用");
          var input = files.get(str(c, "sourceStep", ""));
          require(str(c, "sourceStep", "").isBlank() || input != null, "来源步骤未生成文件");
          if (c.get("certificateId") != null)
            input =
                new Artifact(
                    export(id(c, "certificateId"), str(c, "exportFormat", "PEM")),
                    str(c, "filename", "certificate.pem"));
          String filename =
              str(c, "filename", input == null ? "certificate.pem" : input.filename());
          logs.append(
              "SYNC",
              "TRANSFER_STARTED",
              "STARTED",
              actor,
              id,
              current,
              "SERVER",
              serverId,
              "READ".equals(c.get("operation"))
                  ? "开始从服务器“" + server.get("alias") + "”读取文件：" + c.get("sourcePath")
                  : "MOVE".equals(c.get("operation"))
                      ? "服务器“"
                          + server.get("alias")
                          + "”移动文件："
                          + c.get("sourcePath")
                          + " -> "
                          + c.get("targetPath")
                      : "开始向服务器“" + server.get("alias") + "”传输文件“" + filename + "”",
              Map.of("serverAlias", server.get("alias"), "filename", filename));
          if (type.equals("SFTP") && "READ".equals(c.get("operation"))) {
            int allowance = (int) Math.min(10 * 1024 * 1024, 25 * 1024 * 1024 - totalBytes);
            var file = transport.readFile(serverId, id(server, "configId"), c, allowance);
            files.put(current, new Artifact(file.bytes(), file.filename()));
            totalBytes += file.bytes().length;
            result =
                Map.of(
                    "filename",
                    file.filename(),
                    "bytes",
                    file.bytes().length,
                    "sourcePath",
                    file.sourcePath(),
                    "message",
                    "SFTP读取成功：" + file.sourcePath());
          } else
            result =
                transport.execute(
                    serverId,
                    id(server, "configId"),
                    c,
                    input == null ? null : input.bytes(),
                    filename);
        }
        db.update(
            "update sync_run_steps set status='SUCCESS',finished_at=current_timestamp,result_json=? where run_id=? and step_key=?",
            workflows.encode(result),
            id,
            current);
        logs.append(
            "SYNC",
            "STEP_SUCCESS",
            "SUCCESS",
            actor,
            id,
            current,
            "SYNC_RUN",
            id,
            currentDescription
                + "成功"
                + (result.get("message") instanceof String message ? "：" + message : ""),
            result);
      }
      db.update(
          "update sync_runs set status='SUCCESS',finished_at=current_timestamp,lease_until=null where id=? and lease_owner=?",
          id,
          owner);
      audit(id, "SUCCESS");
      logs.append(
          "SYNC",
          "RUN_SUCCESS",
          "SUCCESS",
          actor,
          id,
          null,
          "SYNC_RUN",
          id,
          "同步任务“" + taskName + "”执行成功",
          Map.of());
    } catch (Exception failure) {
      com.certmanage.common.RawExecutionLog.exception(failure);
      String error =
          currentDescription
              + (failure instanceof com.certmanage.server.RemoteFailure remote
                  ? "失败：" + remote.getMessage() + "。"
                  : "执行失败。")
              + "请检查证书、连接配置、远端权限及成功判定；远端操作可能已生效，请确认后重跑。";
      if (current != null)
        db.update(
            "update sync_run_steps set status='FAILED',finished_at=current_timestamp,error_summary=? where run_id=? and step_key=?",
            error,
            id,
            current);
      if (current != null)
        logs.append(
            "SYNC",
            "STEP_FAILURE",
            "FAILURE",
            actor,
            id,
            current,
            "SYNC_RUN",
            id,
            error,
            Map.of("errorType", failure.getClass().getSimpleName()));
      for (String skipped :
          db.queryForList(
              "select step_key from sync_run_steps where run_id=? and status='PENDING'",
              String.class,
              id))
        logs.append(
            "SYNC",
            "STEP_SKIPPED",
            "SKIPPED",
            actor,
            id,
            skipped,
            "SYNC_RUN",
            id,
            "前序步骤失败，本步骤跳过",
            Map.of());
      db.update(
          "update sync_run_steps set status='SKIPPED',finished_at=current_timestamp where run_id=? and status='PENDING'",
          id);
      db.update(
          "update sync_runs set status='FAILED',finished_at=current_timestamp,error_summary=?,lease_until=null where id=? and lease_owner=?",
          error,
          id,
          owner);
      audit(id, "FAILURE");
      logs.append(
          "SYNC",
          "RUN_FAILURE",
          "FAILURE",
          actor,
          id,
          null,
          "SYNC_RUN",
          id,
          error,
          Map.of("errorType", failure.getClass().getSimpleName()));
    } finally {
      files.values().forEach(a -> Arrays.fill(a.bytes(), (byte) 0));
      active.remove(id);
      try {
        logs.append(
            "SYNC",
            "ARTIFACTS_RELEASED",
            "INFO",
            actor,
            id,
            null,
            "SYNC_RUN",
            id,
            "执行产物内存已释放",
            Map.of("count", files.size(), "bytes", totalBytes));
      } finally {
        rawScope.close();
      }
    }
  }

  /**
   * @description 按配置导出证书公钥文件
   * @author Freddy
   * @date 2026-11-12
   * @param asset 操作所需的配置或依赖
   * @param format 操作所需的配置或依赖
   * @return 无效配置时抛出异常，否则返回处理结果
   * @throws Exception 远端操作、证书解析或文件处理失败
   */
  private byte[] export(long asset, String format) throws Exception {
    var chain = certs.chainCertificates(asset);
    return switch (format) {
      case "DER" -> chain.getLast().getEncoded();
      case "PEM" -> crypto.pem(chain.getLast()).getBytes(StandardCharsets.US_ASCII);
      case "CHAIN_PEM" -> {
        StringBuilder b = new StringBuilder();
        for (var cert : chain) b.append(crypto.pem(cert));
        yield b.toString().getBytes(StandardCharsets.US_ASCII);
      }
      case "P7B" ->
          java.security.cert.CertificateFactory.getInstance("X.509")
              .generateCertPath(chain.reversed())
              .getEncoded("PKCS7");
      default -> throw new IllegalArgumentException("公钥导出格式错误");
    };
  }

  /**
   * @description 延长当前进程运行任务的执行租约
   * @author Freddy
   * @date 2026-11-12
   */
  public void heartbeat() {
    for (var e : active.entrySet())
      db.update(
          "update sync_runs set lease_until=dateadd('MINUTE',5,current_timestamp) where id=? and lease_owner=? and status='RUNNING'",
          e.getKey(),
          e.getValue());
  }

  /**
   * @description 将进程中断的任务和步骤标记为终止
   * @author Freddy
   * @date 2026-11-12
   */
  public void recover() {
    var interrupted =
        db.queryForList("select id from sync_runs where status in('QUEUED','RUNNING')", Long.class);
    for (long run : interrupted) {
      for (var step :
          db.queryForList(
              "select step_key,status from sync_run_steps where run_id=? and status in('RUNNING','PENDING')",
              run))
        logs.append(
            "SYNC",
            "STEP_INTERRUPTED",
            step.get("STATUS").equals("RUNNING") ? "INTERRUPTED" : "SKIPPED",
            "system",
            run,
            step.get("STEP_KEY").toString(),
            "SYNC_RUN",
            run,
            "进程重启中断步骤，远端结果可能不确定",
            Map.of());
      logs.append(
          "SYNC",
          "RUN_INTERRUPTED",
          "INTERRUPTED",
          "system",
          run,
          null,
          "SYNC_RUN",
          run,
          "进程重启中断执行，不自动重放",
          Map.of());
    }
    db.update(
        "update sync_run_steps set status='FAILED',finished_at=current_timestamp,error_summary='进程中断，远端结果可能不确定' where status='RUNNING'");
    db.update(
        "update sync_run_steps set status='SKIPPED',finished_at=current_timestamp where status='PENDING' and run_id in(select id from sync_runs where status in('QUEUED','RUNNING'))");
    db.update(
        "update sync_runs set status='INTERRUPTED',finished_at=current_timestamp,error_summary='进程重启中断任务；不自动重放远端操作',lease_until=null where status in('QUEUED','RUNNING')");
  }

  /**
   * @description 记录同步运行审计结果
   * @author Freddy
   * @date 2026-11-12
   * @param run 操作所需的配置或依赖
   * @param outcome 操作所需的配置或依赖
   */
  private void audit(long run, String outcome) {
    db.update(
        "insert into audit_events(actor_name,action_code,target_type,target_id,outcome) values('sync-worker','SYNC_EXECUTION','SYNC_RUN',?,?)",
        Long.toString(run),
        outcome);
  }

  private record Artifact(byte[] bytes, String filename) {}
}
