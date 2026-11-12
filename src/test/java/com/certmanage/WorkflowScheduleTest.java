package com.certmanage;
import com.certmanage.certificate.CertificateService;
import com.certmanage.server.ServerService;
import com.certmanage.sync.WorkflowService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:schedule;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key","app.sync.worker-enabled=false"})
class WorkflowScheduleTest {
 @Autowired WorkflowService workflows;@Autowired ServerService servers;@Autowired CertificateService certs;@Autowired JdbcTemplate db;
 @Test void overlapAdvancesScheduleWithoutRepeatedEnqueue(){long cert=((Number)certs.issue(Map.of("kind","ROOT","cn","Schedule Root","days",3650),"admin").get("id")).longValue();long server=((Number)servers.save(null,Map.of("alias","Schedule Server","type","HTTP","config",Map.of("baseUrl","http://127.0.0.1:1"))).get("id")).longValue();long task=((Number)workflows.save(null,Map.of("name","Schedule Task","state","READY","scheduleEnabled",true,"steps",List.of(Map.of("key","upload","type","HTTP","config",Map.of("serverId",server,"certificateId",cert,"bodyMode","BINARY")))),"admin").get("id")).longValue();workflows.enqueue(task,"manual-queued","admin");db.update("update sync_tasks set next_fire_at=dateadd('MINUTE',-1,current_timestamp) where id=?",task);assertDoesNotThrow(workflows::schedule);assertTrue(db.queryForObject("select next_fire_at from sync_tasks where id=?",OffsetDateTime.class,task).isAfter(OffsetDateTime.now()));assertEquals(1,db.queryForObject("select count(*) from sync_runs where task_id=? and status='QUEUED'",Integer.class,task));long count=db.queryForObject("select count(*) from sync_runs where task_id=?",Long.class,task);workflows.schedule();assertEquals(count,db.queryForObject("select count(*) from sync_runs where task_id=?",Long.class,task));}
}
