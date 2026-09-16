package org.simplepoint.plugin.ai.core.service.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts provider credentials before they are persisted.
 */
@Component
public class AiCredentialCipher {

  private static final String PREFIX = "v1:";

  private static final int IV_LENGTH = 12;

  private static final int TAG_LENGTH_BITS = 128;

  private final AiProperties properties;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Creates the credential cipher.
   *
   * @param properties AI integration properties
   */
  public AiCredentialCipher(final AiProperties properties) {
    this.properties = properties;
  }

  /**
   * Encrypts a plaintext credential using AES-GCM.
   *
   * @param plaintext credential value
   * @return versioned ciphertext
   */
  public String encrypt(final String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
      payload.put(iv).put(encrypted);
      return PREFIX + Base64.getEncoder().encodeToString(payload.array());
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("AI 凭证加密失败", ex);
    }
  }

  /**
   * Decrypts a stored credential.
   *
   * @param ciphertext versioned ciphertext
   * @return plaintext credential
   */
  public String decrypt(final String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      return null;
    }
    if (!ciphertext.startsWith(PREFIX)) {
      throw new IllegalStateException("无法识别的 AI 凭证密文版本");
    }
    try {
      byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
      if (payload.length <= IV_LENGTH) {
        throw new IllegalStateException("AI 凭证密文无效");
      }
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      byte[] iv = new byte[IV_LENGTH];
      buffer.get(iv);
      byte[] encrypted = new byte[buffer.remaining()];
      buffer.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("AI 凭证解密失败，请检查加密主密钥", ex);
    }
  }

  private SecretKeySpec key() throws GeneralSecurityException {
    String configured = properties.getCredentialEncryptionKey();
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException(
          "未配置 simplepoint.ai.credential-encryption-key，无法保存或使用供应商凭证"
      );
    }
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(configured.getBytes(StandardCharsets.UTF_8));
    return new SecretKeySpec(digest, "AES");
  }
}
