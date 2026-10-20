package io.dispatchgrid.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CryptoService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec encryptionKey;

  public CryptoService(@Value("${dispatchgrid.encryption-key}") String encodedKey) {
    byte[] bytes = Base64.getDecoder().decode(encodedKey);
    if (bytes.length != 32)
      throw new IllegalArgumentException("ENCRYPTION_KEY must be base64-encoded 32 random bytes");
    encryptionKey = new SecretKeySpec(bytes, "AES");
  }

  public String randomToken() {
    return randomToken(32);
  }

  public String randomToken(int bytes) {
    byte[] token = new byte[bytes];
    RANDOM.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  public String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD("dispatchgrid:signing-secret:v1".getBytes(StandardCharsets.UTF_8));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return "v1:"
          + Base64.getEncoder()
              .encodeToString(
                  ByteBuffer.allocate(nonce.length + ciphertext.length)
                      .put(nonce)
                      .put(ciphertext)
                      .array());
    } catch (Exception e) {
      throw new IllegalStateException("Secret encryption failed", e);
    }
  }

  public String decrypt(String encrypted) {
    try {
      if (!encrypted.startsWith("v1:")) throw new IllegalArgumentException("Unknown secret format");
      ByteBuffer data = ByteBuffer.wrap(Base64.getDecoder().decode(encrypted.substring(3)));
      byte[] nonce = new byte[12];
      data.get(nonce);
      byte[] ciphertext = new byte[data.remaining()];
      data.get(ciphertext);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD("dispatchgrid:signing-secret:v1".getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Secret decryption failed", e);
    }
  }

  public String sign(String secret, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (Exception e) {
      throw new IllegalStateException("Signing failed", e);
    }
  }

  public boolean verify(
      String secret, String timestamp, byte[] body, String signatures, Instant now) {
    try {
      long seconds = Long.parseLong(timestamp);
      if (seconds < now.getEpochSecond() - 300 || seconds > now.getEpochSecond() + 300)
        return false;
      byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
      for (String signature : signatures.split(",")) {
        if (MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.US_ASCII)))
          return true;
      }
    } catch (RuntimeException ignored) {
      return false;
    }
    return false;
  }
}
