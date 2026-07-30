package org.simplepoint.plugin.ai.mcp.api.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal compact HMAC codec for internal Skill Capability Tokens.
 *
 * <p>This is intentionally not an external OAuth access token. It is an
 * internal, short-lived, single-use capability with a fixed header and signed
 * JSON claims.</p>
 */
public final class McpSkillCapabilityTokenCodec {

  private static final String HEADER =
      base64Url("{\"alg\":\"HS256\",\"typ\":\"SPCT\",\"v\":1}"
          .getBytes(StandardCharsets.UTF_8));

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private static final int MINIMUM_KEY_BYTES = 32;

  private static final int MAXIMUM_TOKEN_LENGTH = 16 * 1024;

  private McpSkillCapabilityTokenCodec() {
  }

  /**
   * Encodes and signs claims.
   */
  public static String issue(
      final ObjectMapper objectMapper,
      final McpSkillCapabilityClaims claims,
      final String signingKey
  ) {
    if (objectMapper == null || claims == null) {
      throw new IllegalArgumentException(
          "Skill capability claims must not be null"
      );
    }
    try {
      String payload = base64Url(objectMapper.writeValueAsBytes(claims));
      String content = HEADER + "." + payload;
      return content + "." + base64Url(sign(content, key(signingKey)));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Skill capability claims cannot be encoded",
          ex
      );
    }
  }

  /**
   * Verifies and decodes claims without applying business policy.
   */
  public static McpSkillCapabilityClaims verify(
      final ObjectMapper objectMapper,
      final String token,
      final String signingKey
  ) {
    if (objectMapper == null || token == null || token.isBlank()
        || token.length() > MAXIMUM_TOKEN_LENGTH) {
      throw invalid();
    }
    String[] segments = token.trim().split("\\.", -1);
    if (segments.length != 3 || !HEADER.equals(segments[0])
        || segments[1].isBlank() || segments[2].isBlank()) {
      throw invalid();
    }
    String content = segments[0] + "." + segments[1];
    byte[] actualSignature;
    byte[] payload;
    try {
      actualSignature = Base64.getUrlDecoder().decode(segments[2]);
      payload = Base64.getUrlDecoder().decode(segments[1]);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
    byte[] expectedSignature = sign(content, key(signingKey));
    if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
      throw invalid();
    }
    try {
      return objectMapper.readValue(payload, McpSkillCapabilityClaims.class);
    } catch (IOException ex) {
      throw invalid();
    }
  }

  private static byte[] key(final String signingKey) {
    if (signingKey == null
        || signingKey.getBytes(StandardCharsets.UTF_8).length
        < MINIMUM_KEY_BYTES) {
      throw new IllegalStateException(
          "Skill capability signing key must contain at least 32 bytes"
      );
    }
    return signingKey.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] sign(final String content, final byte[] key) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(
          "Skill capability token signing is unavailable",
          ex
      );
    }
  }

  private static String base64Url(final byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Skill capability token is invalid");
  }
}
