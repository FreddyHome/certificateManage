package com.certmanage.common;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import static com.certmanage.common.Errors.require;

/** Append-only business execution events; never accepts raw transport bodies or exception text. */
@Service
public class ExecutionLog {
 private final JdbcTemplate db;private final ObjectMapper json;
 private final java.util.Queue<Pending> pending=new java.util.concurrent.ConcurrentLinkedQueue<>();
 private record Pending(OffsetDateTime occurredAt,String source,String code,String outcome,String actor,Long run,String step,String targetType,Long target,String message,Map<String,?> details){}
 private static final Set<String> FIELDS=Set.of("elapsedMs","bytes","entries","filename","statusCode","serverAlias","triggerType","revision","count","attempt","deliveryId","thresholdDays","errorType");
 public ExecutionLog(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 /** A completed remote side effect must never be retried merely because its audit insert failed. */
 public void appendAfterEffect(String source,String code,String outcome,String actor,Long run,String step,String targetType,Long target,String message,Map<String,?> details){OffsetDateTime occurredAt=OffsetDateTime.now();actor=resolveActor(actor);try{append(source,code,outcome,actor,run,step,targetType,target,message,details);}catch(org.springframework.dao.DataAccessException failure){pending.add(new Pending(occurredAt,source,code,outcome,actor,run,step,targetType,target,message,details==null?Map.of():new LinkedHashMap<>(details)));org.slf4j.LoggerFactory.getLogger(getClass()).error("已完成操作的审计事件暂未写入，将重试写库，不重放远端操作");}}
 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000,initialDelay=10000)
 public synchronized void flushPending(){for(int i=0;i<200;i++){var event=pending.peek();if(event==null)return;try{insert(event.occurredAt,event.source,event.code,event.outcome,event.actor,event.run,event.step,event.targetType,event.target,event.message,event.details);pending.remove();}catch(org.springframework.dao.DataAccessException unavailable){return;}}}
 public void append(String source,String code,String outcome,String actor,Long run,String step,String targetType,Long target,String message,Map<String,?> details){
  insert(OffsetDateTime.now(),source,code,outcome,resolveActor(actor),run,step,targetType,target,message,details);
 }
 private String resolveActor(String actor){if(actor==null||actor.endsWith("-worker")){var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();return authentication!=null&&authentication.isAuthenticated()&&!"anonymousUser".equals(authentication.getName())?authentication.getName():"system";}return actor;}
 private void insert(OffsetDateTime occurredAt,String source,String code,String outcome,String actor,Long run,String step,String targetType,Long target,String message,Map<String,?> details){
  var safe=new LinkedHashMap<String,Object>();if(details!=null)for(var e:details.entrySet())if(FIELDS.contains(e.getKey())&&(e.getValue() instanceof Number||e.getValue() instanceof Boolean||e.getValue() instanceof String))safe.put(e.getKey(),e.getValue() instanceof String s?s.substring(0,Math.min(s.length(),300)):e.getValue());
  try{db.update("insert into execution_logs(occurred_at,source,event_code,outcome,actor_name,run_id,step_key,target_type,target_id,message,detail_json) values(?,?,?,?,?,?,?,?,?,?,?)",occurredAt,source,code,outcome,actor==null?"system":actor,run,step,targetType,target,message,json.writeValueAsString(safe));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("审计日志编码失败",e);}
 }
 public Map<String,Object> list(String source,Long run,String outcome,String from,String to,int page,int size){
  require(page>=1&&page<=1000000&&size>=1&&size<=200,"分页参数不合法");var where=new StringBuilder(" where 1=1");var args=new ArrayList<Object>();
  if(source!=null&&!source.isBlank()){where.append(" and source=?");args.add(source);}if(run!=null){where.append(" and run_id=?");args.add(run);}if(outcome!=null&&!outcome.isBlank()){where.append(" and outcome=?");args.add(outcome);}
  try{if(from!=null&&!from.isBlank()){where.append(" and occurred_at>=?");args.add(OffsetDateTime.parse(from));}if(to!=null&&!to.isBlank()){where.append(" and occurred_at<=?");args.add(OffsetDateTime.parse(to));}}catch(Exception e){throw new IllegalArgumentException("时间必须包含时区");}
  long total=db.queryForObject("select count(*) from execution_logs"+where,Long.class,args.toArray());var queryArgs=new ArrayList<>(args);queryArgs.add(size);queryArgs.add((page-1)*size);
  var rows=db.query("select * from execution_logs"+where+" order by occurred_at desc,id desc limit ? offset ?",(r,n)->{var m=new LinkedHashMap<String,Object>();m.put("id",r.getLong("id"));m.put("occurredAt",r.getObject("occurred_at",OffsetDateTime.class).toString());m.put("source",r.getString("source"));m.put("eventCode",r.getString("event_code"));m.put("outcome",r.getString("outcome"));m.put("actor",r.getString("actor_name"));m.put("runId",r.getObject("run_id"));m.put("stepKey",r.getString("step_key"));m.put("targetType",r.getString("target_type"));m.put("targetId",r.getObject("target_id"));m.put("message",r.getString("message"));try{m.put("details",json.readValue(r.getString("detail_json"),Map.class));}catch(Exception e){throw new IllegalStateException(e);}return m;},queryArgs.toArray());return Map.of("items",rows,"total",total,"page",page,"size",size);
 }
}
