package com.certmanage.auth;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.*;
import java.util.Map;
import static com.certmanage.common.Errors.require;
@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 private final UserDetailsService users;private final PasswordEncoder encoder;private final JdbcTemplate db;
 public AuthController(UserDetailsService users,PasswordEncoder encoder,JdbcTemplate db){this.users=users;this.encoder=encoder;this.db=db;}
 @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
 @PostMapping("/login") public Map<String,String> login(@RequestBody Map<String,String> body,HttpServletRequest request){var user=users.loadUserByUsername(body.getOrDefault("username",""));if(!user.isEnabled()||!encoder.matches(body.getOrDefault("password",""),user.getPassword()))throw new BadCredentialsException("登录失败");var previous=request.getSession(false);if(previous!=null)previous.invalidate();var session=request.getSession(true);var row=db.queryForMap("select id,auth_epoch from app_users where username=?",user.getUsername());session.setAttribute("userId",row.get("ID"));session.setAttribute("authEpoch",row.get("AUTH_EPOCH"));var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities()));SecurityContextHolder.setContext(context);session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);return Map.of("username",user.getUsername());}
 @GetMapping("/me") public Map<String,String> me(Authentication a){return Map.of("username",a.getName());}
 @PostMapping("/logout") public void logout(HttpServletRequest r){var s=r.getSession(false);if(s!=null)s.invalidate();SecurityContextHolder.clearContext();}
 @PutMapping("/password") public void password(@RequestBody Map<String,String>b,Authentication a){var u=users.loadUserByUsername(a.getName());require(encoder.matches(b.getOrDefault("oldPassword",""),u.getPassword()),"原密码不正确");String p=b.getOrDefault("password","");require(p.length()>=6&&p.length()<=100,"密码长度6-100位");db.update("update app_users set password_hash=?,auth_epoch=auth_epoch+1,updated_at=current_timestamp where username=?",encoder.encode(p),a.getName());}
}
