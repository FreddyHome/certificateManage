package com.certmanage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.certmanage.certificate.CertificateService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
class ChainDetailTest {
 @Autowired CertificateService service;
 @Test void eachNodeIncludesOwnMetadata(){long id=((Number)service.issue(Map.of("kind","ROOT","cn","Metadata Root","days",3650),"admin").get("id")).longValue();var node=service.chain(id).getFirst();assertEquals("RSA",node.get("algorithm"));assertNotNull(node.get("notBefore"));assertNotNull(node.get("serial"));}
}
