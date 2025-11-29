package org.simplepoint.cloud.oauth.server.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

/**
 * Simple TOTP service compatible with common authenticators (Google Authenticator, Authy).
 * Implements RFC 6238 using HMAC-SHA1, 30-second time step, 6-digit codes.
 */
@Service
public class TotpService {

  private static final String HMAC_ALGO = "HmacSHA1";
  private static final int TIME_STEP_SECONDS = 30;
  private static final int DIGITS = 6;
  private static final Base32 BASE32 = new Base32();

  /**
   * Generate a random secret encoded with Base32 for use with TOTP apps.
   */
  public String generateSecret() {
    byte[] randomBytes = new byte[20];
    new java.security.SecureRandom().nextBytes(randomBytes);
    // Remove padding '=' for nicer secrets; Authy/GA handle this fine
    return BASE32.encodeToString(randomBytes).replace("=", "");
  }

  /**
   * Build an otpauth:// URL that authenticator apps can scan.
   */
  public String buildOtpAuthUrl(String issuer, String accountName, String secret) {
    String label = urlEncode(issuer) + ":" + urlEncode(accountName);
    String issuerParam = urlEncode(issuer);
    return String.format("otpauth://totp/%s?secret=%s&issuer=%s&digits=%d&period=%d",
        label, secret, issuerParam, DIGITS, TIME_STEP_SECONDS);
  }

  /**
   * Verify a TOTP code against the shared secret allowing small clock skew.
   */
  public boolean verifyCode(String secret, String code, Instant now) {
    if (secret == null || secret.isEmpty() || code == null || code.isEmpty()) {
      return false;
    }
    long timeStep = now.getEpochSecond() / TIME_STEP_SECONDS;
    try {
      int codeInt = Integer.parseInt(code.trim());
      // Allow one time step of skew in each direction
      for (long offset = -1; offset <= 1; offset++) {
        int expected = generateCode(secret, timeStep + offset);
        if (expected == codeInt) {
          return true;
        }
      }
      return false;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private int generateCode(String base32Secret, long timeStep) {
    // Decode Base32 secret using Apache Commons Codec
    byte[] key = BASE32.decode(base32Secret);
    byte[] data = ByteBuffer.allocate(8).putLong(timeStep).array();
    try {
      Mac mac = Mac.getInstance(HMAC_ALGO);
      mac.init(new SecretKeySpec(key, HMAC_ALGO));
      byte[] hash = mac.doFinal(data);
      int offset = hash[hash.length - 1] & 0x0F;
      int binary = ((hash[offset] & 0x7F) << 24)
          | ((hash[offset + 1] & 0xFF) << 16)
          | ((hash[offset + 2] & 0xFF) << 8)
          | (hash[offset + 3] & 0xFF);
      return binary % (int) Math.pow(10, DIGITS);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to generate TOTP", e);
    }
  }

  private static String urlEncode(String value) {
    try {
      return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
          .replace("+", "%20");
    } catch (Exception e) {
      return value;
    }
  }
}
