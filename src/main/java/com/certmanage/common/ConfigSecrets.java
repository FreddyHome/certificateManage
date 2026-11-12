package com.certmanage.common;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@Component
public class ConfigSecrets {
 private final JdbcTemplate db; private final SecretStore store; private final ObjectMapper json;
 public ConfigSecrets(JdbcTemplate d,SecretStore s,ObjectMapper j){db=d;store=s;json=j;}
 public Long save(Map<String,Object> values){try{var sealed=store.seal(json.writeValueAsBytes(values));var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("insert into sync_secret_bundles(ciphertext,nonce) values(?,?)",new String[]{"id"});p.setBytes(1,sealed.ciphertext());p.setBytes(2,sealed.nonce());return p;},key);return key.getKey().longValue();}catch(Exception e){throw new IllegalStateException("秘密保存失败",e);}}
 public Map<String,Object> read(Long id){if(id==null)return new LinkedHashMap<>();return db.queryForObject("select ciphertext,nonce from sync_secret_bundles where id=?",(r,n)->{try{return json.readValue(store.open(r.getBytes(1),r.getBytes(2)),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("秘密读取失败",e);}},id);}
}
