package org.simplepoint.security.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Versioned authenticated encryption for authentication secrets. Keys are supplied in a read-only
 * secret file, never derived from a default password or stored alongside ciphertext.
 */
@Component
public final class AuthenticationSecretCipher {
  private static final String PREFIX = "totp:v1:";
  private static final byte[] PURPOSE =
      "simplepoint.authentication.totp.v1".getBytes(StandardCharsets.UTF_8);
  private final SecureRandom random = new SecureRandom();
  private final Map<String, SecretKeySpec> keys = new HashMap<>();
  private final String activeKeyId;

  /** Loads and validates an externally provisioned encryption key ring. */
  public AuthenticationSecretCipher(
      @Value("${simplepoint.security.authentication.key-ring-file:}") String keyFile) {
    if (keyFile.isBlank()) {
      activeKeyId = null;
      return; // Accounts without TOTP can operate; secret operations always fail closed.
    }
    try {
      Path path = Path.of(keyFile);
      if (Files.size(path) > 16384) {
        throw new IllegalArgumentException("Authentication key ring exceeds size limit");
      }
      JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(path));
      activeKeyId = root.path("activeKeyId").asText();
      root.path("keys")
          .fields()
          .forEachRemaining(
              entry -> {
                if (!entry.getKey().matches("[a-zA-Z0-9_-]{1,32}")) {
                  throw new IllegalArgumentException("Invalid authentication key id");
                }
                byte[] key = Base64.getDecoder().decode(entry.getValue().asText());
                if (key.length != 32) {
                  throw new IllegalArgumentException(
                      "Authentication encryption requires a 256-bit key");
                }
                keys.put(entry.getKey(), new SecretKeySpec(key, "AES"));
              });
      if (keys.size() > 8 || !keys.containsKey(activeKeyId)) {
        throw new IllegalArgumentException("Invalid active authentication key");
      }
    } catch (Exception ex) {
      // Do not attach parser exceptions: they can include the secret file contents.
      throw new IllegalStateException("Cannot load authentication key ring");
    }
  }

  /** Encrypts an enrollment seed with a fresh nonce and its immutable account identity. */
  public String encrypt(String userId, String secret) {
    requireKey();
    if (secret == null || !secret.matches("[A-Z2-7]{16,128}")) {
      throw new IllegalArgumentException("Invalid authentication secret");
    }
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce, userId);
      return PREFIX
          + activeKeyId
          + ":"
          + encode(nonce)
          + ":"
          + encode(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot protect authentication secret");
    }
  }

  /** Authenticates the ciphertext and owner before recovering the seed. */
  public String decrypt(String userId, String stored) {
    requireKey();
    try {
      String[] parts = stored.split(":", -1);
      if (parts.length != 5 || !stored.startsWith(PREFIX) || !keys.containsKey(parts[2])) {
        throw new IllegalArgumentException();
      }
      byte[] nonce = Base64.getUrlDecoder().decode(parts[3]);
      if (nonce.length != 12 || parts[4].length() > 256) {
        throw new IllegalArgumentException();
      }
      return new String(
          cipher(Cipher.DECRYPT_MODE, keys.get(parts[2]), nonce, userId)
              .doFinal(Base64.getUrlDecoder().decode(parts[4])),
          StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot recover authentication secret");
    }
  }

  /** Explicit migration only: ordinary reads never accept legacy plaintext. */
  public String migrate(String userId, String stored) {
    String clear = stored.startsWith(PREFIX) ? decrypt(userId, stored) : stored;
    return encrypt(userId, clear);
  }

  /** Rejects secret operations when encryption has not been provisioned. */
  public void requireKey() {
    if (activeKeyId == null) {
      throw new IllegalStateException("Authentication key ring is required");
    }
  }

  private Cipher cipher(int mode, SecretKeySpec key, byte[] nonce, String userId) throws Exception {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("Authentication secret owner is required");
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD(PURPOSE);
    cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
