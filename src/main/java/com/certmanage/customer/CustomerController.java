package com.certmanage.customer;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.text.Normalizer;
import com.certmanage.common.Audit;
import java.security.Principal;
import static com.certmanage.common.Errors.require;
@RestController @RequestMapping("/api/v1/customers")
public class CustomerController {
 private final JdbcTemplate db;private final Audit audit;public CustomerController(JdbcTemplate db,Audit audit){this.db=db;this.audit=audit;}
 public static String key(String n){return Normalizer.normalize(n.trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
 @GetMapping public List<Map<String,Object>> list(){return db.query("select * from customers order by name_key",(r,n)->Map.of("id",r.getLong("id"),"name",r.getString("customer_name"),"description",Objects.toString(r.getString("description"),""),"externalId",Objects.toString(r.getString("external_id"),""),"idType",Objects.toString(r.getString("id_type"),"")));}
 @PostMapping @Transactional public Map<String,Object> add(@RequestBody Map<String,String>b,Principal p){String n=b.getOrDefault("name","").trim();require(!n.isEmpty()&&n.length()<=200,"客户名称必填且不超过200字");db.update("insert into customers(customer_name,name_key,description,external_id,id_type) values(?,?,?,?,?)",n,key(n),b.getOrDefault("description",""),optional(b,"externalId",200),optional(b,"idType",100));audit.record(p.getName(),"CUSTOMER_CREATE","CUSTOMER",n);return Map.of("name",n);}
 @PatchMapping("/{id}") @Transactional public void edit(@PathVariable long id,@RequestBody Map<String,String>b,Principal p){String n=b.getOrDefault("name","").trim();require(!n.isEmpty()&&n.length()<=200,"客户名称必填且不超过200字");require(db.update("update customers set customer_name=?,name_key=?,description=?,external_id=?,id_type=?,updated_at=current_timestamp where id=?",n,key(n),b.getOrDefault("description",""),optional(b,"externalId",200),optional(b,"idType",100),id)==1,"客户不存在");audit.record(p.getName(),"CUSTOMER_EDIT","CUSTOMER",id);}
 private String optional(Map<String,String>b,String field,int max){String value=Objects.toString(b.get(field),"").trim();require(value.length()<=max,field+" exceeds maximum length "+max);return value.isEmpty()?null:value;}
}
