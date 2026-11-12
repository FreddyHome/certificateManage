package com.certmanage.server;
import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/servers")
public class ServerController {
 final com.certmanage.common.ExecutionLog logs;final ServerService service;final RemoteTransport transport;final com.certmanage.common.Audit audit;
 public ServerController(ServerService s,RemoteTransport t,com.certmanage.common.Audit a,com.certmanage.common.ExecutionLog logs){service=s;transport=t;audit=a;this.logs=logs;}
 @GetMapping public Object list(){return service.list();}
 @GetMapping("/{id}") public Object get(@PathVariable long id){return service.get(id);}
 @PostMapping public Object create(@RequestBody Map<String,Object> body,java.security.Principal actor){var result=service.save(null,body);audit.record(actor.getName(),"SERVER_CREATE","SERVER",result.get("id"));return result;}
 @PutMapping("/{id}") public Object update(@PathVariable long id,@RequestBody Map<String,Object> body,java.security.Principal actor){var result=service.save(id,body);audit.record(actor.getName(),"SERVER_UPDATE","SERVER",id);return result;}
 @DeleteMapping("/{id}") public void delete(@PathVariable long id,java.security.Principal actor){service.delete(id);audit.record(actor.getName(),"SERVER_DELETE","SERVER",id);}
 @PostMapping("/{id}/test") public Object test(@PathVariable long id,java.security.Principal actor){logs.append("SERVER","TEST_STARTED","STARTED",actor.getName(),null,null,"SERVER",id,"服务器连通性测试开始",Map.of());try{var result=transport.test(id);boolean success=Boolean.TRUE.equals(result.get("success"));logs.append("SERVER",success?"TEST_SUCCESS":"TEST_FAILURE",success?"SUCCESS":"FAILURE",actor.getName(),null,null,"SERVER",id,success?"服务器连通性测试成功":"服务器连通性测试失败："+result.get("message"),result);audit.record(actor.getName(),"SERVER_TEST","SERVER",id,success?"SUCCESS":"FAILURE",success?"服务器 #"+id+" 连通性测试成功":"服务器 #"+id+" 连通性测试失败："+result.get("message"));return result;}catch(RuntimeException failure){logs.append("SERVER","TEST_FAILURE","FAILURE",actor.getName(),null,null,"SERVER",id,"服务器测试未完成，请检查配置",Map.of());throw failure;}}
}

