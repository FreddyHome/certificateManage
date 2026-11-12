package com.certmanage;
import com.certmanage.common.ExecutionLog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:audit;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key","app.sync.worker-enabled=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ExecutionLogTest {
 @Autowired ExecutionLog log;
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;
 @Test void recordsFailedOperationsAndProtectsLogAccess()throws Exception{mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/customers").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content("{\"name\":\"\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());var events=log.list("OPERATION",null,"FAILURE",null,null,1,50).toString();assertTrue(events.contains("admin"));assertTrue(events.contains("客户"));mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/execution-logs")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());}
 @Test void appendOnlyWhitelistedResultsAndPagedQuery(){log.append("SERVER","TEST_SUCCESS","SUCCESS","admin",null,null,"SERVER",42L,"服务器测试成功",Map.of("elapsedMs",20,"password","dont-store-secret","body","dont-store-body"));var page=log.list("SERVER",null,"SUCCESS",null,null,1,10);assertEquals(1L,((Number)page.get("total")).longValue());assertFalse(page.toString().contains("dont-store"));assertTrue(page.toString().contains("elapsedMs"));assertThrows(IllegalArgumentException.class,()->log.list(null,null,null,null,null,0,10));}
}
