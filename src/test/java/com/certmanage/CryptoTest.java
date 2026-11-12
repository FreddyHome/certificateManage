package com.certmanage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.certmanage.certificate.CertificateCrypto;
import java.util.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
class CryptoTest {
 @Autowired CertificateCrypto crypto;
 @Test void buildsUnorderedChainAndRejectsMissingRoot() throws Exception {
 var root=crypto.issue("ROOT","Test Root",3650,null,null,List.of(),"GENERAL");
 var middle=crypto.issue("INTERMEDIATE","Test Middle",1000,root.cert(),root.key(),List.of(),"GENERAL");
 var leaf=crypto.issue("LEAF","server.test",90,middle.cert(),middle.key(),List.of("server.test"),"HTTPS");
 assertEquals("Test Root",crypto.cn(crypto.chain(List.of(leaf.cert(),root.cert(),middle.cert())).getFirst()));
 assertThrows(IllegalArgumentException.class,()->crypto.chain(List.of(leaf.cert(),middle.cert())));
 assertThrows(IllegalArgumentException.class,()->crypto.match(leaf.cert(),root.key()));
 crypto.match(leaf.cert(),leaf.key());
 }
}
