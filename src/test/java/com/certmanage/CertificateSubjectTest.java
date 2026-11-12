package com.certmanage;
import com.certmanage.certificate.CertificateService;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
class CertificateSubjectTest {
 @Autowired CertificateService service; @Autowired TransactionTemplate tx;
 @Test void writesSubjectFieldsWithoutChangingIssuer(){tx.execute(s->{var fields=new HashMap<String,Object>(Map.of("kind","ROOT","cn","Subject Root","days",3650,"country","cn","state","Zhejiang","locality","Hangzhou","organization","Example, Inc.","organizationalUnit","IT"));long root=((Number)service.issue(fields,"admin").get("id")).longValue();var cert=service.chainCertificates(root).getLast();var subject=X500Name.getInstance(cert.getSubjectX500Principal().getEncoded());assertEquals("CN",subject.getRDNs(BCStyle.C)[0].getFirst().getValue().toString());assertEquals("Hangzhou",subject.getRDNs(BCStyle.L)[0].getFirst().getValue().toString());assertEquals("Zhejiang",subject.getRDNs(BCStyle.ST)[0].getFirst().getValue().toString());assertEquals("Example, Inc.",subject.getRDNs(BCStyle.O)[0].getFirst().getValue().toString());assertEquals("IT",subject.getRDNs(BCStyle.OU)[0].getFirst().getValue().toString());fields.putAll(Map.of("kind","INTERMEDIATE","cn","Subject Intermediate","days",1825,"issuerId",root,"locality","Shanghai"));long mid=((Number)service.issue(fields,"admin").get("id")).longValue();var child=service.chainCertificates(mid).getLast();assertEquals(cert.getSubjectX500Principal(),child.getIssuerX500Principal());assertEquals("Shanghai",X500Name.getInstance(child.getSubjectX500Principal().getEncoded()).getRDNs(BCStyle.L)[0].getFirst().getValue().toString());fields.putAll(Map.of("kind","LEAF","cn","Subject Leaf","days",365,"issuerId",mid));long leaf=((Number)service.issue(fields,"admin").get("id")).longValue();assertEquals(3,new com.certmanage.certificate.CertificateCrypto().chain(service.chainCertificates(leaf)).size());s.setRollbackOnly();return null;});}
 @Test void rejectsInvalidCountry(){assertThrows(IllegalArgumentException.class,()->service.issue(Map.of("kind","ROOT","cn","Invalid Country","country","China"),"admin"));}
}

