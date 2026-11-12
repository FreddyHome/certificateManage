package com.certmanage.config;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
 @Bean UserDetailsService users(JdbcTemplate db){return name->{var rows=db.queryForList("select * from app_users where username=?",name);if(rows.isEmpty())throw new UsernameNotFoundException("用户不存在");var r=rows.getFirst();return User.withUsername(name).password((String)r.get("PASSWORD_HASH")).disabled(!(Boolean)r.get("ENABLED")).roles("ADMIN").build();};}
 @Bean SecurityFilterChain security(HttpSecurity http,JdbcTemplate db) throws Exception {
 http.csrf(c->c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
 http.authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login").permitAll().requestMatchers("/api/**").hasRole("ADMIN").anyRequest().permitAll());
 http.exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->{s.setStatus(401);s.setContentType("application/json");s.getWriter().write("{\"message\":\"请登录\"}");}));
 http.addFilterBefore(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
 var session=r.getSession(false);if(session!=null&&session.getAttribute("userId")!=null){long id=(Long)session.getAttribute("userId");var rows=db.queryForList("select enabled,auth_epoch from app_users where id=?",id);if(rows.isEmpty()||!(Boolean)rows.getFirst().get("ENABLED")||!rows.getFirst().get("AUTH_EPOCH").equals(session.getAttribute("authEpoch"))){session.invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();}}
 chain.doFilter(r,s);
 }},UsernamePasswordAuthenticationFilter.class);
 http.headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")));
 return http.build();
 }
}
