package org.simplepoint.cloud.oauth.server.configuration.vault;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 PS256 token 使用 Vault 公钥（kid=2）能够成功验签，避免线上验签差异。
 */
class VaultTransitJwtEncoderTest {

  private static final String PUBLIC_KEY_PEM = """
      -----BEGIN PUBLIC KEY-----
      MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA6RMqw/Lm/vuSMsDEmS9g
      OehFRV8mNneWpd9imYGuN/6luT12uv6NzEtiIoDa/+bvy7uWJxFCqVHo0m+NL+B9
      I3DPqHsucyqzmGoX9YSYepKZL0GtOEApJftRkIkMYdE/UiYOYIF05YLwqP6B2AC5
      ej/Tv1rcnkCxm7fjQiaDOH7wtNRj7vnKCgwLZl2wg7SY4b1/pfEPW9biljd7PtYn
      X7Uf7FQue1WYKM6wX1ZGJ2rcF0Zj2poBsBBWHixIWQmhMGjUDHsJPfGoRQb5tRHw
      cEDCBXFlKReYHknM85d4qZQQ4OD0DCn5rF14+IF1N9rnNbr7VhHxwr6P0eDbTzP3
      HQIDAQAB
      -----END PUBLIC KEY-----
      """;

  private static final String SAMPLE_TOKEN =
      "eyJraWQiOiIyIiwidHlwIjoiSldUIiwiYWxnIjoiUFMyNTYifQ."
          + "eyJzdWIiOiJzeXN0ZW0iLCJhdWQiOlsic2ltcGxlcG9pbnQtY2xpZW50Il0sImF6cCI6InNpbXBsZXBvaW50LWNsaWVudCIsImF1dGhfdGltZSI6MTc2ODk2Mjk3ODEwMCwiaXNzIjoiaHR0cDovLzEyNy4wLjAuMTo5MDAwIiwiZXhwIjoxNzY4OTY0ODE2LjIyNTA5MzE2MSwiaWF0IjoxNzY4OTYzMDE2LjIyNTA5MzE2MSwibm9uY2UiOiJZOHl1Q3daaXdkVWF1N2FqN3JSWEVUbUJDa3NzcTJYTExYNnl6M0E0cjhzIiwianRpIjoiOTNlMzg3ZWEtNjc4Zi00NDFlLWJlYjMtMjAxNDk3NTUwNWUyIiwic2lkIjoiUXNWNW1JTjJWVDFORkVKNkIzendDZWFOMWlVN0NXdk1aam9aQ1h3OUZ2byJ9."
          + "roxw9wKJVHERAYNKKL304nq9zVmHaORA5dZYwKU-JAIEDm6pFs_K9SYz6OeknPQAdHvOOvE1KRN2meYUku1RT4rObChNddU6L8Xuxr9Kvy6fZpuE7LpysF8NctTwUw_EQ-OlftUEs_K9JOwPR11hCcFrw5SpZILjl7dr9CxDDMCicfpN6LD-waLM8kUJ7pbkLeCsETRqdSmGsbfdwM8l5Cs12QuOTrxlPi97-mFfRAHT5GpW5AcJfCKV_AK8hAzJmeFvA4zZb2vORzl6jJIrkdVtSOHQ1v9AgCT39OxJnQv247X7qfFHaRjsCD54KeFQN1CH73p197SnOtemxSYqoQ";

  @Test
  void verifySamplePs256TokenWithKid2() throws Exception {
    RSAPublicKey publicKey = parseRsaPublicKey(PUBLIC_KEY_PEM);
    SignedJWT jwt = SignedJWT.parse(SAMPLE_TOKEN);

    assertThat(jwt.getHeader().getAlgorithm()).as("alg").isEqualTo(JWSAlgorithm.PS256);
    assertThat(jwt.getHeader().getKeyID()).as("kid").isEqualTo("2");

    byte[] signingInput = (jwt.getParsedParts()[0].toString() + "." + jwt.getParsedParts()[1].toString())
        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] signature = jwt.getSignature().decode();

    int hashLen = 32; // SHA-256
    int keyBytes = (publicKey.getModulus().bitLength() + 7) / 8;
    int maxSaltLen = keyBytes - hashLen - 2; // RFC 8017 upper bound

    boolean verified = verifyPss(publicKey, signingInput, signature, hashLen)
        || verifyPss(publicKey, signingInput, signature, maxSaltLen);

    assertThat(verified).as("signature should be valid").isTrue();
  }

  private RSAPublicKey parseRsaPublicKey(String pem) throws Exception {
    String clean = pem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replaceAll("[\\r\\n\\t ]", "");
    byte[] der = Base64.getDecoder().decode(clean);
    X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
  }

  private boolean verifyPss(RSAPublicKey key, byte[] data, byte[] sig, int saltLen) {
    try {
      Signature verifier = Signature.getInstance("RSASSA-PSS");
      PSSParameterSpec params = new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, saltLen, 1);
      verifier.setParameter(params);
      verifier.initVerify(key);
      verifier.update(data);
      return verifier.verify(sig);
    } catch (Exception e) {
      return false;
    }
  }
}
