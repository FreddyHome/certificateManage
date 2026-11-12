package com.certmanage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
@AutoConfigureMockMvc
class ApplicationTest {
 @Autowired MockMvc mvc;
 @Test void rejectsAnonymous() throws Exception {mvc.perform(get("/api/v1/certificates")).andExpect(status().isUnauthorized());}
 @Test void initialAdminCanLogin() throws Exception {mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"123456\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));}
}
