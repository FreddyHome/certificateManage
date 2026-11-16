package com.certmanage.common;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretStore {
  private final byte[] master;
  private final Path directory;

  public SecretStore(@Value("${app.encryption-key-file}") String file) throws Exception {
    Path p = Path.of(file);
    directory = p.toAbsolutePath().getParent();
    Files.createDirectories(p.toAbsolutePath().getParent());
    if (!Files.exists(p)) {
      byte[] k = new byte[32];
      new SecureRandom().nextBytes(k);
      try {
        Files.write(p, k, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException ignored) {
      }
    }
    master = Files.readAllBytes(p);
    if (master.length != 32) throw new IllegalStateException("加密密钥文件须为32字节");
  }

  /**
   * @description 返回审计加密待写目录所属的密钥目录，不公开密钥内容
   * @author Freddy
   * @date 2026-11-16
   * @return 密钥父目录
   */
  public Path directory() {
    return directory;
  }

  public record Sealed(byte[] ciphertext, byte[] nonce) {}

  public Sealed seal(byte[] data) {
    try {
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
      return new Sealed(c.doFinal(data), nonce);
    } catch (Exception e) {
      throw new IllegalStateException("秘密加密失败", e);
    }
  }

  public byte[] open(byte[] bytes, byte[] nonce) {
    try {
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
      return c.doFinal(bytes);
    } catch (Exception e) {
      throw new IllegalStateException("秘密解密失败，检查密钥文件", e);
    }
  }
}
