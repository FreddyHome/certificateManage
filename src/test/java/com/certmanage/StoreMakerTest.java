package com.certmanage;
import com.certmanage.certificate.*;
import com.certmanage.sync.StoreMaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.KeyStore;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"})
class StoreMakerTest {
 @Autowired StoreMaker maker; @Autowired CertificateService certs; @Autowired JdbcTemplate db;
 @Test void bothFormatsContainPrivateChainOrPublicOnly()throws Exception{
  long root=((Number)certs.issue(Map.of("kind","ROOT","cn","Store Root","days",3650),"admin").get("id")).longValue();
  long material=db.queryForObject("select material_id from certificate_assets where id=?",Long.class,root);
  for(String format:List.of("JKS","PKCS12")){
   byte[] bytes=maker.make("KEYSTORE",Map.of("format",format,"resolvedEntries",List.of(Map.of("assetId",root,"materialId",material,"alias","server","role","PRIVATE"))),Map.of("password","storepass123","keyPassword",format.equals("JKS")?"keypass123":"storepass123"));
   var ks=KeyStore.getInstance(format);ks.load(new ByteArrayInputStream(bytes),"storepass123".toCharArray());assertTrue(ks.isKeyEntry("server"));assertEquals(1,ks.getCertificateChain("server").length);assertNotNull(ks.getKey("server",(format.equals("JKS")?"keypass123":"storepass123").toCharArray()));
   bytes=maker.make("TRUSTSTORE",Map.of("format",format,"resolvedEntries",List.of(Map.of("assetId",root,"materialId",material,"alias","trusted","role","TRUST"))),Map.of("password","storepass123"));
   ks=KeyStore.getInstance(format);ks.load(new ByteArrayInputStream(bytes),"storepass123".toCharArray());assertTrue(ks.isCertificateEntry("trusted"));assertFalse(ks.isKeyEntry("trusted"));
  }
 }
}
