package com.certmanage.certificate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import com.certmanage.common.Audit;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static com.certmanage.common.Errors.require;
@RestController @RequestMapping("/api/v1")
public class CertificateController {
 private final com.certmanage.common.IdempotentActions idem;private final CertificateService service;private final CertificateCrypto crypto;private final Audit audit;
 public CertificateController(CertificateService s,CertificateCrypto c,Audit a,com.certmanage.common.IdempotentActions i){service=s;crypto=c;audit=a;idem=i;}
 @GetMapping("/certificates") public Object list(@RequestParam(defaultValue="OWN")String owner){return service.list(owner);}
 @GetMapping("/certificates/{id}") public Object detail(@PathVariable long id){return service.detail(id);}
 @DeleteMapping("/certificates/{id}") public void delete(@PathVariable long id,Principal p){service.deleteOwn(id,p.getName());}
 @GetMapping("/certificates/{id}/chain") public Object chain(@PathVariable long id){service.detail(id);return service.chain(id);}
 @GetMapping("/issuers") public Object issuers(@RequestParam(defaultValue="LEAF")String childKind){return service.issuers(childKind);}
 @PostMapping("/certificates/issue") public Object issue(@RequestBody Map<String,Object>b,@RequestHeader(value="X-Idempotency-Key",required=false)String requestKey,Principal p)throws Exception{String digest=crypto.fingerprint(new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(b));return idem.execute(p.getName(),"ISSUE",requestKey,digest,()->service.issue(b,p.getName()));}
 @PostMapping(value={"/imports/preview","/imports/commit"},consumes="multipart/form-data") public Object upload(@RequestParam String owner,@RequestParam String mode,@RequestParam String purpose,@RequestParam(required=false)String customer,@RequestParam(defaultValue="")String password,@RequestParam("files")List<MultipartFile>files,@RequestParam(required=false)MultipartFile key, jakarta.servlet.http.HttpServletRequest req,Principal p)throws Exception{require(Set.of("OWN","CUSTOMER").contains(owner),"归属错误");var bytes=new ArrayList<byte[]>();for(var f:files)bytes.add(f.getBytes());var parsed=crypto.parse(mode,bytes,key==null?null:key.getBytes(),password,owner.equals("OWN"));if(req.getRequestURI().endsWith("preview"))return Map.of("chain",parsed.chain().stream().map(c->crypto.cn(c)).toList(),"keyMatched",parsed.key()!=null);String digest=crypto.fingerprint((owner+"|"+com.certmanage.customer.CustomerController.key(Objects.toString(customer,""))+"|"+purpose+"|"+parsed.chain().stream().map(c->{try{return crypto.fingerprint(c.getEncoded());}catch(Exception e){throw new IllegalStateException(e);}}).toList()).getBytes(StandardCharsets.UTF_8));return idem.execute(p.getName(),"IMPORT",req.getHeader("X-Idempotency-Key"),digest,()->service.saveImport(parsed,owner,customer,purpose,p.getName()));}
 @PostMapping("/certificates/{id}/export") public ResponseEntity<byte[]> export(@PathVariable long id,@RequestBody Map<String,String>b,Principal principal)throws Exception{var d=service.detail(id);var chain=service.chainCertificates(id);String format=b.getOrDefault("format","PEM");byte[] bytes;String ext;
 switch(format){case "DER"-> {bytes=chain.getLast().getEncoded();ext="cer";}
 case "PEM"->{StringBuilder pem=new StringBuilder();for(var c:chain)pem.append(crypto.pem(c));bytes=pem.toString().getBytes(StandardCharsets.US_ASCII);ext="pem";}
 case "P7B"->{bytes=java.security.cert.CertificateFactory.getInstance("X.509").generateCertPath(chain.reversed()).getEncoded("PKCS7");ext="p7b";}
 case "P12"->{require("OWN".equals(d.get("owner")),"客户证书不可导出私钥");String password=b.getOrDefault("password","");require(password.length()>=8,"导出密码至少8位");var key=service.key(id);var store=KeyStore.getInstance("PKCS12");store.load(null,null);store.setKeyEntry("certificate",key,password.toCharArray(),chain.reversed().toArray(X509Certificate[]::new));var out=new java.io.ByteArrayOutputStream();store.store(out,password.toCharArray());bytes=out.toByteArray();ext="p12";}
 default->throw new IllegalArgumentException("不支持的导出格式");}
 audit.record(principal.getName(),format.equals("P12")?"PRIVATE_KEY_EXPORT":"CERTIFICATE_EXPORT","CERTIFICATE",id);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=certificate-"+id+"."+ext).contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);}
}

