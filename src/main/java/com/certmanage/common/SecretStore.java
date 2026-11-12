package com.certmanage.common;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
@Component
public class SecretStore {
 private final byte[] master;
 public SecretStore(@Value("${app.encryption-key-file}")String file)throws Exception{Path p=Path.of(file);Files.createDirectories(p.toAbsolutePath().getParent());if(!Files.exists(p)){byte[] k=new byte[32];new SecureRandom().nextBytes(k);try{Files.write(p,k,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}}master=Files.readAllBytes(p);if(master.length!=32)throw new IllegalStateException("加密密钥文件须为32字节");}
 public record Sealed(byte[] ciphertext,byte[] nonce){}
 public Sealed seal(byte[] data){try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,nonce));return new Sealed(c.doFinal(data),nonce);}catch(Exception e){throw new IllegalStateException("秘密加密失败",e);}}
 public byte[] open(byte[] bytes,byte[] nonce){try{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,nonce));return c.doFinal(bytes);}catch(Exception e){throw new IllegalStateException("秘密解密失败，检查密钥文件",e);}}
}
