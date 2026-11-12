package com.certmanage;
import com.certmanage.certificate.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
class CertificateDeleteTest {
 @Autowired CertificateService service; @Autowired TransactionTemplate tx;
 long issue(String kind,String cn,Long parent){var b=new HashMap<String,Object>();b.put("kind",kind);b.put("cn",cn);b.put("days",kind.equals("ROOT")?3650:kind.equals("INTERMEDIATE")?1825:365);if(parent!=null)b.put("issuerId",parent);return ((Number)service.issue(b,"admin").get("id")).longValue();}
 @Test void protectsAncestorsAndAllowsDeletingFromLeafUp(){tx.execute(s->{long root=issue("ROOT","Delete Root",null),mid=issue("INTERMEDIATE","Delete Mid",root),leaf=issue("LEAF","Delete Leaf",mid);assertThrows(IllegalArgumentException.class,()->service.deleteOwn(root,"admin"));assertThrows(IllegalArgumentException.class,()->service.deleteOwn(mid,"admin"));service.deleteOwn(leaf,"admin");assertThrows(IllegalArgumentException.class,()->service.detail(leaf));service.deleteOwn(mid,"admin");service.deleteOwn(root,"admin");assertFalse(service.list("OWN").stream().anyMatch(r->r.get("id").equals(root)));s.setRollbackOnly();return null;});}
 @Test void customerChainAlsoProtectsOwnCertificates(){tx.execute(s->{long root=issue("ROOT","Customer Ref Root",null),mid=issue("INTERMEDIATE","Customer Ref Mid",root),leaf=issue("LEAF","Customer Ref Leaf",mid);long customer=((Number)service.saveImport(new CertificateCrypto.Parsed(service.chainCertificates(leaf),null),"CUSTOMER","Delete Test Customer","GENERAL","admin").get("id")).longValue();assertThrows(IllegalArgumentException.class,()->service.deleteOwn(leaf,"admin"));assertThrows(IllegalArgumentException.class,()->service.deleteOwn(root,"admin"));assertThrows(IllegalArgumentException.class,()->service.deleteOwn(customer,"admin"));s.setRollbackOnly();return null;});}
}

