package com.certmanage.sync;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
@Component
@ConditionalOnProperty(name="app.sync.worker-enabled",havingValue="true",matchIfMissing=true)
public class WorkflowWorker {
 private final com.certmanage.common.ExecutionLog logs;private final WorkflowService service;private final WorkflowRunner runner;
 public WorkflowWorker(WorkflowService service,WorkflowRunner runner,com.certmanage.common.ExecutionLog logs){this.service=service;this.runner=runner;this.logs=logs;}
 @EventListener(ApplicationReadyEvent.class) public void recover(){runner.recover();}
 @Scheduled(fixedDelay=1000,initialDelay=10000) public void execute(){try{runner.runNext();}catch(Exception e){logs.append("SCHEDULER","WORKER_FAILURE","FAILURE","system",null,null,null,null,"同步后台任务异常",java.util.Map.of());org.slf4j.LoggerFactory.getLogger(getClass()).warn("同步执行暂未完成，请检查数据库状态");}}
 @Scheduled(fixedDelay=10000,initialDelay=10000) public void schedule(){try{service.schedule();}catch(Exception e){logs.append("SCHEDULER","WORKER_FAILURE","FAILURE","system",null,null,null,null,"同步后台任务异常",java.util.Map.of());org.slf4j.LoggerFactory.getLogger(getClass()).warn("同步调度暂未完成，请检查任务配置");}}
 @Scheduled(fixedDelay=30000,initialDelay=10000) public void heartbeat(){runner.heartbeat();}
}
