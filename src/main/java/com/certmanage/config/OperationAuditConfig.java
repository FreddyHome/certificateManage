package com.certmanage.config;
import com.certmanage.common.ExecutionLog;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

@Configuration
public class OperationAuditConfig implements WebMvcConfigurer {
 private final ExecutionLog logs;public OperationAuditConfig(ExecutionLog logs){this.logs=logs;}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
  @Override public boolean preHandle(HttpServletRequest r,HttpServletResponse s,Object handler){if(r.getUserPrincipal()!=null)r.setAttribute("operationActor",r.getUserPrincipal().getName());return true;}
  @Override public void afterCompletion(HttpServletRequest r,HttpServletResponse s,Object handler,Exception ex){String path=r.getRequestURI();if(path.equals("/api/v1/auth/csrf")||path.equals("/api/v1/auth/me")||path.startsWith("/api/v1/execution-logs"))return;if(Boolean.TRUE.equals(r.getAttribute("operationAudited"))&&s.getStatus()<400)return;String actor=Objects.toString(r.getAttribute("operationActor"),r.getUserPrincipal()==null?"anonymous":r.getUserPrincipal().getName());boolean ok=s.getStatus()<400&&ex==null;String resource=path.contains("/auth/")?"账号认证":path.contains("/certificates")||path.contains("/imports")||path.contains("/issuers")?"证书":path.contains("/customers")?"客户":path.contains("/users")?"用户":path.contains("/servers")?"服务器":path.contains("/sync-")?"同步任务":path.contains("smtp")?"SMTP配置":path.contains("notification")?"通知服务":"系统";String action=switch(r.getMethod()){case "GET"->"查询";case "DELETE"->"删除";case "PUT","PATCH"->"修改";default->"操作";};String description=path.endsWith("/login")?"用户登录":path.endsWith("/logout")?"用户退出":path.endsWith("/password")?"修改账号密码":action+resource;logs.appendAfterEffect("OPERATION","API_"+r.getMethod(),ok?"SUCCESS":"FAILURE",actor,null,null,null,null,description+(ok?"成功":"失败"),Map.of("statusCode",s.getStatus()));}
 }).addPathPatterns("/api/v1/**");}
}
