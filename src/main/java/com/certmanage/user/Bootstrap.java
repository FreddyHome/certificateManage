package com.certmanage.user;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class Bootstrap {
 @Bean ApplicationRunner initialAdmin(JdbcTemplate db,PasswordEncoder encoder,TransactionTemplate tx){return args->tx.execute(s->{if(db.queryForObject("select count(*) from app_bootstrap where bootstrap_key='admin'",Integer.class)==0){if(db.queryForObject("select count(*) from app_users where username='admin'",Integer.class)==0)db.update("insert into app_users(username,password_hash) values('admin',?)",encoder.encode("123456"));db.update("merge into app_user_roles(user_id,role_id) key(user_id,role_id) select u.id,r.id from app_users u,app_roles r where u.username='admin' and r.role_code='ADMIN'");db.update("insert into app_bootstrap(bootstrap_key) values('admin')");}long actor=db.queryForObject("select min(id) from app_users",Long.class);if(db.queryForObject("select count(*) from notification_policy",Integer.class)==0){db.update("insert into notification_policy(id,enabled,timezone_id,scan_time,warning_days,include_ca,updated_by) values(1,false,'Asia/Shanghai','09:00:00',30,false,?)",actor);for(int days:new int[]{30,14,7,1})db.update("insert into notification_thresholds values(1,?)",days);}return null;});}
}
