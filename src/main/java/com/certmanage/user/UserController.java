package com.certmanage.user;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import com.certmanage.common.Audit;
import java.security.Principal;
import java.util.*;
import static com.certmanage.common.Errors.require;
@RestController @RequestMapping("/api/v1/users")
public class UserController {
 private final JdbcTemplate db;private final PasswordEncoder enc;private final Audit audit;
 public UserController(JdbcTemplate d,PasswordEncoder e,Audit a){db=d;enc=e;audit=a;}
 @GetMapping public List<Map<String,Object>> list(){return db.queryForList("select id,username,enabled,created_at from app_users order by id");}
 @PostMapping @Transactional public void add(@RequestBody Map<String,String>b,Principal p){String n=b.getOrDefault("username","").trim(),pw=b.getOrDefault("password","");require(n.matches("[a-zA-Z0-9_.-]{1,100}")&&pw.length()>=6&&pw.length()<=100,"用户名格式错误或密码不足6位");db.update("insert into app_users(username,password_hash) values(?,?)",n,enc.encode(pw));db.update("insert into app_user_roles select u.id,r.id from app_users u,app_roles r where u.username=? and r.role_code='ADMIN'",n);audit.record(p.getName(),"USER_CREATE","USER",n);}
 @PatchMapping("/{id}") @Transactional public void update(@PathVariable long id,@RequestBody Map<String,Boolean>b,Principal p){db.queryForList("select id from app_users order by id for update");require(db.queryForObject("select count(*) from app_users where id=?",Integer.class,id)>0,"用户不存在");boolean enabled=Boolean.TRUE.equals(b.get("enabled"));if(!enabled){require(db.queryForObject("select count(*) from app_users where enabled=true and id<>?",Integer.class,id)>0,"至少保留一个启用管理员");}db.update("update app_users set enabled=?,auth_epoch=auth_epoch+1,updated_at=current_timestamp where id=?",enabled,id);audit.record(p.getName(),"USER_STATUS","USER",id);}
 @PostMapping("/{id}/reset-password") public void reset(@PathVariable long id,@RequestBody Map<String,String>b,Principal p){String pw=b.getOrDefault("password","");require(pw.length()>=6&&pw.length()<=100,"密码长度6-100位");require(db.update("update app_users set password_hash=?,auth_epoch=auth_epoch+1,updated_at=current_timestamp where id=?",enc.encode(pw),id)==1,"用户不存在");audit.record(p.getName(),"PASSWORD_RESET","USER",id);}
}
