package com.certmanage.common;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/execution-logs")
public class ExecutionLogController {
 private final ExecutionLog log;public ExecutionLogController(ExecutionLog log){this.log=log;}
 @GetMapping public Object list(@RequestParam(required=false)String source,@RequestParam(required=false)Long runId,@RequestParam(required=false)String outcome,@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int size){return log.list(source,runId,outcome,from,to,page,size);}
}
