package com.certmanage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"}) @AutoConfigureMockMvc
class SmtpTest {
 @Autowired MockMvc mvc;
 @Test void savedCredentialNeverReturned()throws Exception{
 mvc.perform(put("/api/v1/system/smtp").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"host\":\"localhost\",\"port\":2525,\"tlsMode\":\"STARTTLS\",\"username\":\"mailer\",\"password\":\"secret-test\",\"sender\":\"certs@example.test\"}")).andExpect(status().isOk());
 mvc.perform(get("/api/v1/system/smtp").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.credentialConfigured").value(true)).andExpect(jsonPath("$.password").doesNotExist());
 }
}
