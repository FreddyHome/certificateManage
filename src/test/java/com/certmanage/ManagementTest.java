package com.certmanage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"}) @AutoConfigureMockMvc
class ManagementTest {
 @Autowired MockMvc mvc;
 @Test void createsCustomerAndRejectsDuplicate() throws Exception {
 mvc.perform(post("/api/v1/customers").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"name\":\"Test Customer\",\"description\":\"Integration\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Test Customer"));
 mvc.perform(post("/api/v1/customers").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"name\":\"Test Customer\"}")).andExpect(status().isConflict());
 }
 @Test void notificationDefaultsDisabled() throws Exception {mvc.perform(get("/api/v1/system/notifications").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));}
 @Test void rejectsMissingSigner() throws Exception {mvc.perform(post("/api/v1/certificates/issue").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"kind\":\"LEAF\",\"cn\":\"example.test\",\"days\":365,\"purpose\":\"HTTPS\"}")).andExpect(status().isUnprocessableEntity());}
}
