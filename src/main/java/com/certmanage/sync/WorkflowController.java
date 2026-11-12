package com.certmanage.sync;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.security.Principal;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class WorkflowController {
 private final WorkflowService service;private final com.certmanage.common.ExecutionLog logs;public WorkflowController(WorkflowService service,com.certmanage.common.ExecutionLog logs){this.service=service;this.logs=logs;}
 @GetMapping("/sync-tasks") public Object list(){return service.list();}
 @PostMapping("/sync-tasks") public Object create(@RequestBody Map<String,Object>b,Principal p){return service.save(null,b,p.getName());}
 @GetMapping("/sync-tasks/{id}") public Object detail(@PathVariable long id){return service.detail(id);}
 @PutMapping("/sync-tasks/{id}") public Object update(@PathVariable long id,@RequestBody Map<String,Object>b,Principal p){return service.save(id,b,p.getName());}
 @DeleteMapping("/sync-tasks/{id}") public void delete(@PathVariable long id,Principal p){service.delete(id,p.getName());}
 @PostMapping("/sync-tasks/{id}/validate") public Object validate(@PathVariable long id){return service.validate(id);}
 @PostMapping("/sync-tasks/{id}/execute") public ResponseEntity<?> execute(@PathVariable long id,@RequestHeader(value="X-Idempotency-Key",required=false)String key,Principal p){logs.append("SYNC","EXECUTE_REQUESTED","INFO",p.getName(),null,null,"SYNC_TASK",id,"用户请求立即执行",Map.of());try{return ResponseEntity.accepted().body(Map.of("id",service.enqueue(id,key,p.getName())));}catch(RuntimeException e){logs.append("SYNC","EXECUTE_REJECTED","FAILURE",p.getName(),null,null,"SYNC_TASK",id,"立即执行未入队，请检查任务状态、引用和配置",Map.of());throw e;}}
 @GetMapping("/sync-tasks/{id}/runs") public Object runs(@PathVariable long id){return service.runs(id);}
 @GetMapping("/sync-runs/{id}") public Object run(@PathVariable long id){return service.runDetail(id);}
}
