package com.certmanage.smtp;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.security.Principal;
import com.certmanage.common.Audit;
@RestController @RequestMapping("/api/v1/system/smtp")
public class SmtpController {
 private final SmtpService smtp;private final Audit audit;public SmtpController(SmtpService s,Audit a){smtp=s;audit=a;}
 @GetMapping public Object get(){return smtp.config();}
 @PutMapping public void put(@RequestBody Map<String,Object>b,Principal p){smtp.save(b,audit.actor(p.getName()));audit.record(p.getName(),"SMTP_CONFIG","SYSTEM",1);}
 @PostMapping("/test") public Map<String,String> test(@RequestBody Map<String,String>b,Principal p){try{smtp.send(b.get("recipient"),"证书管理系统测试邮件","SMTP连接测试成功。");audit.record(p.getName(),"SMTP_TEST","SMTP",1);return Map.of("message","测试邮件已发送");}catch(Exception e){audit.record(p.getName(),"SMTP_TEST","SMTP",1,"FAILURE");throw new IllegalArgumentException("测试发送失败，请检查SMTP、TLS及认证配置");}}
}
