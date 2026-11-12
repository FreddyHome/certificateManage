package com.certmanage.common;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
@Service
public class IdempotentActions {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Audit audit;private final ObjectMapper json;
 public IdempotentActions(JdbcTemplate d,TransactionTemplate t,Audit a,ObjectMapper j){db=d;tx=t;audit=a;json=j;}
 public synchronized Map<String,Object> execute(String user,String operation,String requestKey,String digest,Supplier<Map<String,Object>> action){if(requestKey==null||requestKey.isBlank())return action.get();Errors.require(requestKey.length()<=100,"请求标识过长");long actor=audit.actor(user);var rows=db.queryForList("select * from operation_requests where actor_id=? and operation_type=? and request_key=?",actor,operation,requestKey);if(!rows.isEmpty()){if(!digest.equals(rows.getFirst().get("PAYLOAD_DIGEST")))throw new ResponseStatusException(HttpStatus.CONFLICT,"同一请求标识不能用于不同内容");try{return json.readValue(rows.getFirst().get("RESPONSE_JSON").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException(e);}}return tx.execute(s->{var result=action.get();try{db.update("insert into operation_requests(actor_id,operation_type,request_key,payload_digest,asset_id,response_json) values(?,?,?,?,?,?)",actor,operation,requestKey,digest,result.get("id"),json.writeValueAsString(result));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}return result;});}
}
