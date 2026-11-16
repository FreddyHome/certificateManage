package com.certmanage.smtp;

import static com.certmanage.common.Errors.require;

import com.certmanage.common.SecretStore;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
/**
 * @description 管理SMTP配置及邮件投递，批次内复用配置与连接且不跨批次保留凭据。
 * @author Freddy
 * @date 2026-11-16
 */
public class SmtpService {
  private final ThreadLocal<Batch> activeBatch = new ThreadLocal<>();

  /**
   * @description 为当前投递线程开启惰性批次，保留原send方法以兼容调用及测试拦截。
   * @author Freddy
   * @date 2026-11-16
   * @return 关闭时释放连接及配置快照的批次句柄
   */
  public Batch beginBatch() {
    require(activeBatch.get() == null, "邮件批次不可嵌套");
    var batch = new Batch();
    activeBatch.set(batch);
    return batch;
  }

  /**
   * @description 当前线程的有限邮件批次，仅在首封实际发送时读取配置并连接。
   * @author Freddy
   * @date 2026-11-16
   */
  public final class Batch implements AutoCloseable {
    private Map<String, Object> snapshot;
    private JavaMailSenderImpl sender;
    private Transport transport;

    /**
     * @description 关闭批次传输并清除线程引用，不将关闭失败当作已接受邮件失败。
     * @author Freddy
     * @date 2026-11-16
     */
    public void close() {
      if (transport != null)
        try {
          transport.close();
        } catch (MessagingException ignored) {
        }
      snapshot = null;
      sender = null;
      transport = null;
      activeBatch.remove();
    }
  }

  private final com.certmanage.common.ExecutionLog logs;
  private final JdbcTemplate db;
  private final SecretStore secrets;

  /**
   * @description 初始化配置存储和审计依赖。
   * @author Freddy
   * @date 2026-11-16
   * @param d 数据库组件
   * @param s 秘密存储
   * @param logs 执行日志组件
   */
  public SmtpService(JdbcTemplate d, SecretStore s, com.certmanage.common.ExecutionLog logs) {
    db = d;
    secrets = s;
    this.logs = logs;
  }

  /**
   * @description 返回不含明文凭据的SMTP公开配置。
   * @author Freddy
   * @date 2026-11-16
   * @return 公开配置及凭据存在标记
   */
  public Map<String, Object> config() {
    var rows = db.queryForList("select * from smtp_config where id=1");
    if (rows.isEmpty())
      return Map.of(
          "host",
          "",
          "port",
          587,
          "tlsMode",
          "STARTTLS",
          "username",
          "",
          "sender",
          "",
          "credentialConfigured",
          false);
    var r = rows.getFirst();
    return Map.of(
        "host",
        r.get("SMTP_HOST"),
        "port",
        r.get("SMTP_PORT"),
        "tlsMode",
        r.get("TLS_MODE"),
        "username",
        Objects.toString(r.get("SMTP_USERNAME"), ""),
        "sender",
        r.get("SENDER_ADDRESS"),
        "credentialConfigured",
        r.get("CREDENTIAL_CIPHERTEXT") != null);
  }

  /**
   * @description 校验并保存SMTP配置，空密码保留现有密文。
   * @author Freddy
   * @date 2026-11-16
   * @param b 待保存配置
   * @param user 操作用户标识
   */
  public void save(Map<String, Object> b, long user) {
    String host = Objects.toString(b.get("host"), "").trim(),
        sender = Objects.toString(b.get("sender"), "").trim(),
        mode = Objects.toString(b.get("tlsMode"), "STARTTLS");
    int port = ((Number) b.getOrDefault("port", 587)).intValue();
    require(!host.isBlank() && host.length() <= 255 && port > 0 && port < 65536, "SMTP地址或端口错误");
    require(validEmail(sender), "发件邮箱错误");
    require(Set.of("STARTTLS", "TLS").contains(mode), "必须启用TLS加密");
    var old = db.queryForList("select * from smtp_config where id=1");
    byte[] cipher = null, nonce = null;
    String password = Objects.toString(b.get("password"), "");
    if (!old.isEmpty()) {
      cipher = (byte[]) old.getFirst().get("CREDENTIAL_CIPHERTEXT");
      nonce = (byte[]) old.getFirst().get("CREDENTIAL_NONCE");
    }
    if (!password.isEmpty()) {
      var s = secrets.seal(password.getBytes(StandardCharsets.UTF_8));
      cipher = s.ciphertext();
      nonce = s.nonce();
    }
    db.update(
        "merge into smtp_config(id,smtp_host,smtp_port,tls_mode,smtp_username,credential_ciphertext,credential_nonce,encryption_key_id,sender_address,updated_by,updated_at) key(id) values(1,?,?,?,?,?,?,?,?,?,current_timestamp)",
        host,
        port,
        mode,
        Objects.toString(b.get("username"), ""),
        cipher,
        nonce,
        cipher == null ? null : "master-v1",
        sender,
        user);
  }

  /**
   * @description 校验邮箱格式并拒绝换行注入。
   * @author Freddy
   * @date 2026-11-16
   * @param email 待校验邮箱
   * @return 格式有效时返回true
   */
  public static boolean validEmail(String email) {
    if (email == null || email.length() > 320 || email.contains("\r") || email.contains("\n"))
      return false;
    try {
      var a = new jakarta.mail.internet.InternetAddress(email, true);
      a.validate();
      return a.getAddress().contains("@");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * @description 使用单次SMTP会话发送邮件。
   * @author Freddy
   * @date 2026-11-16
   * @param recipient 收件邮箱
   * @param subject 主题
   * @param text 正文
   */
  public void send(String recipient, String subject, String text) {
    send(recipient, subject, text, null);
  }

  /**
   * @description 发送邮件并保留指定Message-ID及投递审计。
   * @author Freddy
   * @date 2026-11-16
   * @param recipient 收件邮箱
   * @param subject 主题
   * @param text 正文
   * @param messageId 固定邮件标识，空值时自动生成
   */
  public void send(String recipient, String subject, String text, String messageId) {
    logs.append(
        "SMTP",
        "SEND_STARTED",
        "STARTED",
        "smtp-worker",
        null,
        null,
        "SMTP",
        1L,
        "邮件发送开始",
        Map.of());
    try {
      sendMessage(recipient, subject, text, messageId);
      logs.appendAfterEffect(
          "SMTP",
          "SEND_SUCCESS",
          "SUCCESS",
          "smtp-worker",
          null,
          null,
          "SMTP",
          1L,
          "邮件发送成功",
          Map.of());
    } catch (RuntimeException e) {
      logs.append(
          "SMTP",
          "SEND_FAILURE",
          "FAILURE",
          "smtp-worker",
          null,
          null,
          "SMTP",
          1L,
          "邮件发送失败，检查连接、TLS和凭据",
          Map.of());
      throw e;
    }
  }

  /**
   * @description 使用当前批次或独立连接发送邮件，失败交给投递重试处理。
   * @author Freddy
   * @date 2026-11-16
   * @param recipient 收件邮箱
   * @param subject 主题
   * @param text 正文
   * @param messageId 固定邮件标识
   */
  private void sendMessage(String recipient, String subject, String text, String messageId) {
    require(validEmail(recipient), "收件邮箱错误");
    var batch = activeBatch.get();
    Map<String, Object> r;
    JavaMailSenderImpl sender;
    if (batch != null && batch.snapshot != null) {
      r = batch.snapshot;
      sender = batch.sender;
    } else {
      var rows = db.queryForList("select * from smtp_config where id=1");
      require(!rows.isEmpty(), "请先配置SMTP服务");
      r = rows.getFirst();
      sender = configuredSender(r);
      if (batch != null) {
        batch.snapshot = r;
        batch.sender = sender;
      }
    }
    try {
      var mail =
          new jakarta.mail.internet.MimeMessage(sender.getSession()) {
            @Override
            protected void updateMessageID() throws jakarta.mail.MessagingException {
              if (messageId != null) setHeader("Message-ID", messageId);
              else super.updateMessageID();
            }
          };
      var helper = new org.springframework.mail.javamail.MimeMessageHelper(mail, "UTF-8");
      helper.setFrom((String) r.get("SENDER_ADDRESS"));
      helper.setTo(recipient);
      helper.setSubject(subject);
      helper.setText(text);
      if (batch == null) sender.send(mail);
      else {
        try {
          if (batch.transport == null || !batch.transport.isConnected()) {
            batch.transport = sender.getSession().getTransport("smtp");
            batch.transport.connect(
                sender.getHost(), sender.getPort(), sender.getUsername(), sender.getPassword());
          }
          mail.saveChanges();
          batch.transport.sendMessage(mail, mail.getAllRecipients());
        } catch (MessagingException e) {
          if (batch.transport != null)
            try {
              batch.transport.close();
            } catch (MessagingException ignored) {
            }
          batch.transport = null;
          throw e;
        }
      }
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalArgumentException("邮件发送失败", e);
    }
  }

  /**
   * @description 按单次配置快照创建强制TLS且超时有界的邮件客户端。
   * @author Freddy
   * @date 2026-11-16
   * @param r 已保存SMTP配置
   * @return 配置完成的邮件客户端
   */
  JavaMailSenderImpl configuredSender(Map<String, Object> r) {
    var sender = new JavaMailSenderImpl();
    sender.setHost((String) r.get("SMTP_HOST"));
    sender.setPort(((Number) r.get("SMTP_PORT")).intValue());
    sender.setUsername((String) r.get("SMTP_USERNAME"));
    if (r.get("CREDENTIAL_CIPHERTEXT") != null)
      sender.setPassword(
          new String(
              secrets.open(
                  (byte[]) r.get("CREDENTIAL_CIPHERTEXT"), (byte[]) r.get("CREDENTIAL_NONCE")),
              StandardCharsets.UTF_8));
    var props = sender.getJavaMailProperties();
    props.setProperty(
        "mail.smtp.auth",
        Boolean.toString(
            r.get("SMTP_USERNAME") != null && !r.get("SMTP_USERNAME").toString().isBlank()));
    props.setProperty("mail.smtp.connectiontimeout", "10000");
    props.setProperty("mail.smtp.timeout", "10000");
    props.setProperty("mail.smtp.writetimeout", "10000");
    props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    if ("TLS".equals(r.get("TLS_MODE"))) props.setProperty("mail.smtp.ssl.enable", "true");
    else {
      props.setProperty("mail.smtp.starttls.enable", "true");
      props.setProperty("mail.smtp.starttls.required", "true");
    }
    return sender;
  }
}
