package com.certmanage;
import com.certmanage.customer.CustomerController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
class CustomerIdentificationTest {
 @Autowired CustomerController customers;
 @Test @Transactional void identificationCanBeSavedEditedAndCleared(){customers.add(Map.of("name","Identification Customer","externalId","partner-001","idType","AS2"),()->"admin");var row=customers.list().stream().filter(r->r.get("name").equals("Identification Customer")).findFirst().orElseThrow();assertEquals("partner-001",row.get("externalId"));assertEquals("AS2",row.get("idType"));long id=((Number)row.get("id")).longValue();customers.edit(id,Map.of("name","Identification Customer","externalId","","idType",""),()->"admin");row=customers.list().stream().filter(r->r.get("id").equals(id)).findFirst().orElseThrow();assertEquals("",row.get("externalId"));assertEquals("",row.get("idType"));}
}
