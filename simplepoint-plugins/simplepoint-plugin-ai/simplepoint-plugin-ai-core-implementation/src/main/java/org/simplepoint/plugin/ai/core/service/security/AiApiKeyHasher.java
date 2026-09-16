package org.simplepoint.plugin.ai.core.service.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.springframework.stereotype.Component;

/** Generates high-entropy model API keys and stores only a peppered verifier. */
@Component
public class AiApiKeyHasher {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AiProperties properties;

  /** Creates a hasher backed by the configured server-side pepper. */
  public AiApiKeyHasher(final AiProperties properties) {
    this.properties = properties;
  }

  /** Issues a new raw key and its persistent verifier. */
  public IssuedSecret issue() {
    String prefix = "spk_" + randomUrlToken(9);
    String rawKey = prefix + "." + randomUrlToken(32);
    return new IssuedSecret(prefix, rawKey, hash(rawKey));
  }

  /** Extracts the lookup prefix without scanning stored hashes. */
  public String prefix(final String rawKey) {
    if (rawKey == null || rawKey.length() > 256) {
      return null;
    }
    int separator = rawKey.indexOf('.');
    if (separator < 8 || separator > 39 || separator != rawKey.lastIndexOf('.')) {
      return null;
    }
    String prefix = rawKey.substring(0, separator);
    return prefix.startsWith("spk_") ? prefix : null;
  }

  /** Compares a presented key with a stored verifier in constant time. */
  public boolean matches(final String rawKey, final String storedHash) {
    if (rawKey == null || storedHash == null) {
      return false;
    }
    byte[] expected;
    try {
      expected = Base64.getUrlDecoder().decode(storedHash);
    } catch (IllegalArgumentException ex) {
      return false;
    }
    byte[] actual = Base64.getUrlDecoder().decode(hash(rawKey));
    return MessageDigest.isEqual(expected, actual);
  }

  private String hash(final String rawKey) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(requirePepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding()
          .encodeToString(mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("无法初始化模型 API Key 校验器", ex);
    }
  }

  private String requirePepper() {
    String pepper = normalize(properties.getApiKeyHashPepper());
    if (pepper == null) {
      pepper = normalize(properties.getCredentialEncryptionKey());
    }
    if (pepper == null || pepper.length() < 16) {
      throw new IllegalStateException("请配置 simplepoint.ai.api-key-hash-pepper（至少 16 个字符）");
    }
    return pepper;
  }

  private static String randomUrlToken(final int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  /** One-time raw secret paired with its lookup prefix and verifier. */
  public record IssuedSecret(String prefix, String rawKey, String hash) {
  }
}
