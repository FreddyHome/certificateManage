package com.certmanage.smtp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.certmanage.common.ExecutionLog;
import com.certmanage.common.SecretStore;
import jakarta.mail.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * @description 使用内存邮件传输验证批次连接复用、固定邮件标识和失败后的重连。
 * @author Freddy
 * @date 2026-11-16
 */
class SmtpBatchTest {
  /**
   * @description 同批次两封邮件只读取一次配置并连接一次，下一批次重新读取配置。
   * @author Freddy
   * @date 2026-11-16
   * @throws Exception 测试邮件提供者配置失败时抛出
   */
  @Test
  void reusesTransportAndConfigurationButNotAcrossBatches() throws Exception {
    StubTransport.connects = 0;
    StubTransport.messages.clear();
    StubTransport.failNext = false;
    var db = mock(JdbcTemplate.class);
    var config = new HashMap<String, Object>();
    config.put("SENDER_ADDRESS", "sender@example.test");
    when(db.queryForList("select * from smtp_config where id=1")).thenReturn(List.of(config));
    var service = spy(new SmtpService(db, mock(SecretStore.class), mock(ExecutionLog.class)));
    var sender = new JavaMailSenderImpl();
    var session = Session.getInstance(new Properties());
    session.setProvider(
        new Provider(Provider.Type.TRANSPORT, "smtp", StubTransport.class.getName(), "test", "1"));
    sender.setSession(session);
    sender.setHost("unused.test");
    doReturn(sender).when(service).configuredSender(config);
    try (var batch = service.beginBatch()) {
      service.send("one@example.test", "one", "body", "<one@test>");
      service.send("two@example.test", "two", "body", "<two@test>");
    }
    assertEquals(1, StubTransport.connects);
    assertEquals(List.of("<one@test>", "<two@test>"), StubTransport.messages);
    verify(db).queryForList("select * from smtp_config where id=1");
    try (var batch = service.beginBatch()) {
      StubTransport.failNext = true;
      assertThrows(
          IllegalArgumentException.class,
          () -> service.send("one@example.test", "failed", "body", "<retry@test>"));
      service.send("one@example.test", "retry", "body", "<retry@test>");
    }
    assertEquals(3, StubTransport.connects);
    assertEquals("<retry@test>", StubTransport.messages.getLast());
    verify(db, times(2)).queryForList("select * from smtp_config where id=1");
  }

  /**
   * @description 内存SMTP提供者，仅记录消息且不会建立网络连接。
   * @author Freddy
   * @date 2026-11-16
   */
  public static class StubTransport extends Transport {
    static int connects;
    static boolean failNext;
    static List<String> messages = new ArrayList<>();

    /**
     * @description 初始化内存传输提供者。
     * @author Freddy
     * @date 2026-11-16
     * @param session 邮件会话
     * @param url 传输地址
     */
    public StubTransport(Session session, URLName url) {
      super(session, url);
    }

    /**
     * @description 记录模拟连接，不访问网络。
     * @author Freddy
     * @date 2026-11-16
     * @param host 未使用主机
     * @param port 未使用端口
     * @param user 未使用用户
     * @param password 未使用密码
     * @return 始终连接成功
     */
    protected boolean protocolConnect(String host, int port, String user, String password) {
      connects++;
      return true;
    }

    /**
     * @description 记录邮件标识或模拟一次发送失败。
     * @author Freddy
     * @date 2026-11-16
     * @param message 待发送邮件
     * @param addresses 收件人列表
     * @throws MessagingException 配置模拟失败时抛出
     */
    public void sendMessage(Message message, Address[] addresses) throws MessagingException {
      if (failNext) {
        failNext = false;
        throw new MessagingException("stub failure");
      }
      messages.add(message.getHeader("Message-ID")[0]);
    }
  }
}
