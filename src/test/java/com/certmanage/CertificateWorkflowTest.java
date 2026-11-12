package com.certmanage;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import com.certmanage.certificate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1","app.encryption-key-file=target/test-master.key"}) @AutoConfigureMockMvc
class CertificateWorkflowTest {
 @Autowired MockMvc mvc;@Autowired CertificateCrypto crypto;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @Test void repeatedIssueKeyReturnsSameAsset()throws Exception{String body="{\"kind\":\"ROOT\",\"cn\":\"Idempotent Root\",\"days\":3650,\"purpose\":\"GENERAL\"}";var a=mvc.perform(post("/api/v1/certificates/issue").header("X-Idempotency-Key","root-test").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();var b=mvc.perform(post("/api/v1/certificates/issue").header("X-Idempotency-Key","root-test").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertEquals(json.readTree(a).get("id"),json.readTree(b).get("id"));}
 @Test void importsContainersAsIndividualMaterialsAndRejectsCustomerPrivateKey()throws Exception{
 var root=crypto.issue("ROOT","Workflow Root",3650,null,null,List.of(),"GENERAL");var mid=crypto.issue("INTERMEDIATE","Workflow Mid",1000,root.cert(),root.key(),List.of(),"GENERAL");var leaf=crypto.issue("LEAF","workflow.test",90,mid.cert(),mid.key(),List.of(),"AS2");var chain=List.of(root.cert(),mid.cert(),leaf.cert());
 var store=KeyStore.getInstance("PKCS12");store.load(null,null);store.setKeyEntry("leaf",leaf.key(),"test12345".toCharArray(),chain.reversed().toArray(X509Certificate[]::new));var out=new ByteArrayOutputStream();store.store(out,"test12345".toCharArray());
 var result=mvc.perform(multipart("/api/v1/imports/commit").file(new MockMultipartFile("files","cert.p12","application/octet-stream",out.toByteArray())).param("owner","OWN").param("mode","pkcs12").param("purpose","AS2").param("password","test12345").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();long ownId=json.readTree(result).get("id").asLong();assertEquals(3,db.queryForObject("select count(*) from asset_chain_nodes where asset_id=?",Integer.class,ownId));assertEquals(1,db.queryForObject("select count(*) from private_keys where own_asset_id=?",Integer.class,ownId));
 byte[] p7=java.security.cert.CertificateFactory.getInstance("X.509").generateCertPath(chain.reversed()).getEncoded("PKCS7");mvc.perform(multipart("/api/v1/imports/commit").file(new MockMultipartFile("files","cert.p7b","application/octet-stream",p7)).param("owner","CUSTOMER").param("mode","pkcs7").param("purpose","AS2").param("customer","Workflow Customer").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());assertEquals(3,db.queryForObject("select count(*) from certificate_materials where common_name in ('Workflow Root','Workflow Mid','workflow.test')",Integer.class));assertEquals(1,db.queryForObject("select count(*) from customers where customer_name='Workflow Customer'",Integer.class));
 mvc.perform(multipart("/api/v1/imports/commit").file(new MockMultipartFile("files","cert.p7b","application/octet-stream",p7)).file(new MockMultipartFile("key","bad.pem","text/plain",crypto.pem(leaf.key()).getBytes())).param("owner","CUSTOMER").param("mode","pkcs7").param("purpose","AS2").param("customer","Must Not Exist").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isUnprocessableEntity());assertEquals(0,db.queryForObject("select count(*) from customers where customer_name='Must Not Exist'",Integer.class));
 }
}
