package com.certmanage.certificate;
import org.springframework.stereotype.Component;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.*;
import org.bouncycastle.pkcs.*;
import org.bouncycastle.pkcs.jcajce.*;
import org.bouncycastle.operator.jcajce.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import static com.certmanage.common.Errors.require;
@Component
public class CertificateCrypto {
 static {Security.addProvider(new BouncyCastleProvider());}
 public record Pair(X509Certificate cert,PrivateKey key){}
 public record Parsed(List<X509Certificate> chain,PrivateKey key){}
 public String fingerprint(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
 public String cn(X509Certificate c){var r=X500Name.getInstance(c.getSubjectX500Principal().getEncoded()).getRDNs(BCStyle.CN);require(r.length==1&&r[0].getFirst()!=null,"证书须包含唯一 CN");return r[0].getFirst().getValue().toString();}
 public List<X509Certificate> chain(List<X509Certificate> input){try{
 var unique=new LinkedHashMap<String,X509Certificate>();for(var c:input){cn(c);unique.put(fingerprint(c.getEncoded()),c);}var all=new ArrayList<>(unique.values());require(!all.isEmpty()&&all.size()<=10,"证书数量须为1-10");var leaves=all.stream().filter(c->c.getBasicConstraints()<0).toList();require(leaves.size()==1,"必须包含且仅包含一个终端证书");var reverse=new ArrayList<X509Certificate>();var current=leaves.getFirst();
 while(true){require(!reverse.contains(current),"证书链存在循环");reverse.add(current);if(current.getSubjectX500Principal().equals(current.getIssuerX500Principal())){require(current.getBasicConstraints()>=0,"根证书必须是CA");current.verify(current.getPublicKey());break;}var child=current;var parents=all.stream().filter(c->c.getSubjectX500Principal().equals(child.getIssuerX500Principal())).filter(c->{try{child.verify(c.getPublicKey());return true;}catch(Exception e){return false;}}).toList();require(parents.size()==1,"证书链缺失或签发关系不唯一");current=parents.getFirst();require(current.getBasicConstraints()>=0,"签发者不是CA");var usage=current.getKeyUsage();require(usage==null||(usage.length>5&&usage[5]),"CA不具备证书签发用途");}
 require(reverse.size()==all.size(),"存在不属于目标链的额外证书");var root=reverse.getLast();var pathCertificates=new ArrayList<X509Certificate>(reverse);pathCertificates.removeLast();var path=CertificateFactory.getInstance("X.509").generateCertPath(pathCertificates);var params=new PKIXParameters(Set.of(new TrustAnchor(root,null)));params.setRevocationEnabled(false);
 // Validate structure and critical extensions at a point within all certificates' validity.
 long from=all.stream().mapToLong(c->c.getNotBefore().getTime()).max().orElseThrow();long to=all.stream().mapToLong(c->c.getNotAfter().getTime()).min().orElseThrow();require(from<to,"证书链有效区间没有交集");params.setDate(new Date(from+(to-from)/2));CertPathValidator.getInstance("PKIX").validate(path,params);Collections.reverse(reverse);return reverse;
 }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("证书链签名、约束或关键扩展验证失败");}}
 public void match(X509Certificate c,PrivateKey key){try{String alg=switch(c.getPublicKey().getAlgorithm()){case "RSA"->"SHA256withRSA";case "EC","ECDSA"->"SHA256withECDSA";case "Ed25519"->"Ed25519";default->throw new IllegalArgumentException("不支持该私钥算法");};var s=Signature.getInstance(alg);byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);s.initSign(key);s.update(challenge);byte[] signed=s.sign();s.initVerify(c.getPublicKey());s.update(challenge);require(s.verify(signed),"私钥与终端证书不匹配");}catch(Exception e){throw new IllegalArgumentException("私钥与终端证书不匹配");}}
 public Parsed parse(String mode,List<byte[]>files,byte[] keyBytes,String password,boolean own){try{
 require(files.size()>0&&files.size()<=10,"请选择证书文件，最多10个");require(Set.of("separate","pkcs7","pkcs12").contains(mode),"不支持的导入模式");require(own||!mode.equals("pkcs12"),"客户证书不支持P12/PFX");var certs=new ArrayList<X509Certificate>();PrivateKey key=null;char[] pass=password==null?new char[0]:password.toCharArray();
 if(mode.equals("pkcs12")){require(files.size()==1,"只能上传一个证书库");var store=KeyStore.getInstance("PKCS12");store.load(new ByteArrayInputStream(files.getFirst()),pass);int keys=0;for(var names=store.aliases();names.hasMoreElements();){String alias=names.nextElement();if(store.isKeyEntry(alias)){keys++;key=(PrivateKey)store.getKey(alias,pass);var cs=store.getCertificateChain(alias);if(cs!=null)for(var c:cs)certs.add((X509Certificate)c);}else if(store.getCertificate(alias)!=null)certs.add((X509Certificate)store.getCertificate(alias));}require(keys==1,"证书库须包含一个终端私钥");}
 else {if(mode.equals("pkcs7"))require(files.size()==1,"只能上传一个证书库");for(byte[] bytes:files){String text=new String(bytes,StandardCharsets.US_ASCII);require(!text.contains("PRIVATE KEY"),"公钥证书文件中禁止私钥");var parsed=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));require(!parsed.isEmpty(),"证书文件为空");for(var c:parsed)certs.add((X509Certificate)c);if(text.contains("-----BEGIN")){try(var reader=new PEMParser(new StringReader(text))){Object item;while((item=reader.readObject())!=null)require(item instanceof X509CertificateHolder||item instanceof org.bouncycastle.asn1.cms.ContentInfo,"证书文件包含非公钥材料");}}}if(own){require(keyBytes!=null&&keyBytes.length>0,"请上传终端私钥");key=privateKey(keyBytes,pass);}else require(keyBytes==null||keyBytes.length==0,"客户证书禁止私钥");}
 var ordered=chain(certs);if(own){require(key!=null,"缺少终端私钥");match(ordered.getLast(),key);}return new Parsed(ordered,key);
 }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("材料格式或密码错误，无法解析证书/私钥");}}
 public PrivateKey privateKey(byte[] bytes,char[] pass)throws Exception{var converter=new JcaPEMKeyConverter().setProvider("BC");String text=new String(bytes,StandardCharsets.US_ASCII);if(text.contains("-----BEGIN")){try(var parser=new PEMParser(new StringReader(text))){Object o=parser.readObject();PrivateKey key;if(o instanceof PEMEncryptedKeyPair pair)key=converter.getKeyPair(pair.decryptKeyPair(new JcePEMDecryptorProviderBuilder().build(pass))).getPrivate();else if(o instanceof PEMKeyPair pair)key=converter.getKeyPair(pair).getPrivate();else if(o instanceof PKCS8EncryptedPrivateKeyInfo encrypted)key=converter.getPrivateKey(encrypted.decryptPrivateKeyInfo(new JceOpenSSLPKCS8DecryptorProviderBuilder().build(pass)));else if(o instanceof PrivateKeyInfo info)key=converter.getPrivateKey(info);else throw new IllegalArgumentException("不支持的私钥格式");require(parser.readObject()==null,"只允许一个私钥");return key;}}return converter.getPrivateKey(PrivateKeyInfo.getInstance(bytes));}
 public Pair issue(String kind,String cn,int days,X509Certificate issuer,PrivateKey issuerKey,List<String> sans,String purpose)throws Exception{return issue(kind,cn,days,issuer,issuerKey,sans,purpose,Map.of());}
 public Pair issue(String kind,String cn,int days,X509Certificate issuer,PrivateKey issuerKey,List<String> sans,String purpose,Map<String,Object> fields)throws Exception{
 require(Set.of("ROOT","INTERMEDIATE","LEAF").contains(kind),"证书类型错误");require(cn!=null&&!cn.isBlank()&&cn.length()<=200,"CN必填且不超过200字");require(days>0&&days<=36500,"有效天数超出范围");boolean root=kind.equals("ROOT"),ca=!kind.equals("LEAF");require(root||issuer!=null,"必须选择上级签发证书");if(!root){issuer.checkValidity();require(issuer.getBasicConstraints()>=0&&issuerKey!=null,"上级无签发资格");match(issuer,issuerKey);}
 var nameBuilder=new org.bouncycastle.asn1.x500.X500NameBuilder(BCStyle.INSTANCE);
 for(var field:List.of("country","state","locality","organization","organizationalUnit")){Object raw=fields.get(field);require(raw==null||raw instanceof String,"主体字段必须为文本");String value=Objects.toString(raw,"").trim();if(value.isEmpty())continue;require(value.length()<=128&&value.codePoints().noneMatch(Character::isISOControl),"主体字段不得超过128字或包含控制字符");var oid=switch(field){case "country"->BCStyle.C;case "state"->BCStyle.ST;case "locality"->BCStyle.L;case "organization"->BCStyle.O;default->BCStyle.OU;};if(field.equals("country")){value=value.toUpperCase(Locale.ROOT);require(Set.of(Locale.getISOCountries()).contains(value),"国家需填写有效的两位ISO代码，如CN");}nameBuilder.addRDN(oid,value);}
 var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var keys=generator.generateKeyPair();nameBuilder.addRDN(BCStyle.CN,cn.trim());var subject=nameBuilder.build();var issuerName=root?subject:X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded());Instant start=Instant.now().minusSeconds(60),end=Instant.now().plusSeconds(days*86400L);if(!root){require(!end.isAfter(issuer.getNotAfter().toInstant()),"有效期不能超过上级证书");if(start.isBefore(issuer.getNotBefore().toInstant()))start=issuer.getNotBefore().toInstant();}
 var builder=new JcaX509v3CertificateBuilder(issuerName,new BigInteger(159,new SecureRandom()).add(BigInteger.ONE),Date.from(start),Date.from(end),subject,keys.getPublic());
 builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,ca?new BasicConstraints(root?1:0):new BasicConstraints(false));builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new KeyUsage(ca?KeyUsage.keyCertSign|KeyUsage.cRLSign:KeyUsage.digitalSignature|KeyUsage.keyEncipherment));var ext=new JcaX509ExtensionUtils();builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier,false,ext.createSubjectKeyIdentifier(keys.getPublic()));builder.addExtension(org.bouncycastle.asn1.x509.Extension.authorityKeyIdentifier,false,ext.createAuthorityKeyIdentifier(root?keys.getPublic():issuer.getPublicKey()));
 if(!ca&&purpose.equals("HTTPS")){require(!sans.isEmpty(),"HTTPS证书必须填写SAN");builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));}
 if(!ca&&!sans.isEmpty()){var names=new ArrayList<GeneralName>();for(String s:sans){String n=s.trim();if(n.isEmpty())continue;boolean ip=n.matches("[0-9.]+")||n.contains(":");names.add(new GeneralName(ip?GeneralName.iPAddress:GeneralName.dNSName,n));}builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,false,new GeneralNames(names.toArray(GeneralName[]::new)));}
 var signer=new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(root?keys.getPrivate():issuerKey);var cert=new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));cert.verify(root?keys.getPublic():issuer.getPublicKey());return new Pair(cert,keys.getPrivate());
 }
 public String pem(Object object){try{var s=new StringWriter();try(var w=new JcaPEMWriter(s)){w.writeObject(object);}return s.toString();}catch(Exception e){throw new IllegalArgumentException("证书导出失败");}}
}
