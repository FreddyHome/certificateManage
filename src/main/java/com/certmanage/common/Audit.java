package com.certmanage.common;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.Principal;
@Component
public class Audit {
 private final JdbcTemplate db;private final ExecutionLog logs; public Audit(JdbcTemplate db,ExecutionLog logs){this.db=db;this.logs=logs;}
 public long actor(String name){return db.queryForObject("select id from app_users where username=?",Long.class,name);}
 public void record(String name,String action,String type,Object id){record(name,action,type,id,"SUCCESS");}
 public void record(String name,String action,String type,Object id,String outcome){record(name,action,type,id,outcome,null);}
 public void record(String name,String action,String type,Object id,String outcome,String description){Errors.require(java.util.Set.of("SUCCESS","FAILURE").contains(outcome),"审计结果错误");db.update("insert into audit_events(actor_id,actor_name,action_code,target_type,target_id,outcome) values(?,?,?,?,?,?)",actor(name),name,action,type,String.valueOf(id),outcome);Long target=null;try{target=Long.valueOf(String.valueOf(id));}catch(NumberFormatException ignored){}String operation=switch(action){case "CERTIFICATE_ISSUE"->"创建证书";case "CERTIFICATE_IMPORT"->"导入证书";case "CERTIFICATE_DELETE"->"删除证书";case "CERTIFICATE_EXPORT"->"导出公钥证书";case "PRIVATE_KEY_EXPORT"->"导出含私钥证书库";case "CUSTOMER_CREATE"->"新增客户";case "CUSTOMER_EDIT"->"编辑客户";case "USER_CREATE"->"新增用户";case "USER_STATUS"->"修改用户状态";case "PASSWORD_RESET"->"重置用户密码";case "SERVER_CREATE"->"新增服务器";case "SERVER_UPDATE"->"编辑服务器";case "SERVER_DELETE"->"删除服务器";case "SERVER_TEST"->"测试服务器连通性";case "SYNC_TASK_SAVE"->"保存同步任务";case "SYNC_TASK_DELETE"->"删除同步任务";case "SYNC_EXECUTE"->"立即执行同步任务";case "SMTP_CONFIG"->"保存SMTP配置";case "SMTP_TEST"->"发送SMTP测试邮件";case "NOTIFICATION_CONFIG"->"保存通知配置";case "NOTIFICATION_RETRY"->"重试通知投递";case "NOTIFICATION_SCAN"->"扫描到期通知";default->"执行系统操作";};logs.append("OPERATION",action,outcome,name,null,null,type,target,description!=null?description:operation+"（对象："+String.valueOf(id)+"）"+(outcome.equals("SUCCESS")?"成功":"失败"),java.util.Map.of());var request=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();if(request!=null)request.setAttribute("operationAudited",true,org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);}
}
