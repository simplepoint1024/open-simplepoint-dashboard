package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 验证当前 Vault kid=2 公钥能对 PS256 token 成功验签，帮助定位客户端验签失败问题。
 */
class Ps256DecoderTest {

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
  void decodePs256TokenWithPublicKey() throws Exception {
    RSAPublicKey publicKey = parseRsaPublicKey(PUBLIC_KEY_PEM);

    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
        .withPublicKey(publicKey)
        .signatureAlgorithm(SignatureAlgorithm.PS256)
        .build();

    Jwt jwt = decoder.decode(SAMPLE_TOKEN).block();
    assertThat(jwt).isNotNull();
    assertThat(jwt.getHeaders().get("kid")).isEqualTo("2");
    assertThat(jwt.getHeaders().get("alg")).isEqualTo("PS256");
    assertThat(jwt.getSubject()).isEqualTo("system");
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
}
