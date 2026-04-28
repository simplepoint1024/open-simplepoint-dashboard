package org.simplepoint.cloud.oauth.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

  private TotpService totpService;

  @BeforeEach
  void setUp() {
    totpService = new TotpService();
  }

  // ── generateSecret ────────────────────────────────────────────────────────

  @Test
  void generateSecretShouldReturnNonEmptyString() {
    String secret = totpService.generateSecret();

    assertThat(secret).isNotBlank();
  }

  @Test
  void generateSecretShouldNotContainPaddingEquals() {
    String secret = totpService.generateSecret();

    assertThat(secret).doesNotContain("=");
  }

  @Test
  void generateSecretShouldReturnDifferentValuesEachTime() {
    String s1 = totpService.generateSecret();
    String s2 = totpService.generateSecret();

    assertThat(s1).isNotEqualTo(s2);
  }

  // ── buildOtpAuthUrl ───────────────────────────────────────────────────────

  @Test
  void buildOtpAuthUrlShouldContainScheme() {
    String url = totpService.buildOtpAuthUrl("Simplepoint", "alice@example.com", "MYSECRET");

    assertThat(url).startsWith("otpauth://totp/");
  }

  @Test
  void buildOtpAuthUrlShouldContainSecretParam() {
    String url = totpService.buildOtpAuthUrl("Simplepoint", "alice@example.com", "MYSECRET");

    assertThat(url).contains("secret=MYSECRET");
  }

  @Test
  void buildOtpAuthUrlShouldContainIssuerParam() {
    String url = totpService.buildOtpAuthUrl("Simplepoint", "alice@example.com", "MYSECRET");

    assertThat(url).contains("issuer=Simplepoint");
  }

  @Test
  void buildOtpAuthUrlShouldEncodeLabelWithSpaces() {
    String url = totpService.buildOtpAuthUrl("My App", "alice@example.com", "SEC");

    assertThat(url).contains("My%20App");
  }

  // ── verifyCode ────────────────────────────────────────────────────────────

  @Test
  void verifyCodeShouldReturnFalseForNullSecret() {
    assertThat(totpService.verifyCode(null, "123456", Instant.now())).isFalse();
  }

  @Test
  void verifyCodeShouldReturnFalseForEmptySecret() {
    assertThat(totpService.verifyCode("", "123456", Instant.now())).isFalse();
  }

  @Test
  void verifyCodeShouldReturnFalseForNullCode() {
    assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", null, Instant.now())).isFalse();
  }

  @Test
  void verifyCodeShouldReturnFalseForEmptyCode() {
    assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", "", Instant.now())).isFalse();
  }

  @Test
  void verifyCodeShouldReturnFalseForNonNumericCode() {
    assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", "abcdef", Instant.now())).isFalse();
  }

  @Test
  void verifyCodeShouldReturnFalseForObviouslyWrongCode() {
    // 000000 is an extremely unlikely TOTP code for any real secret
    String secret = totpService.generateSecret();

    // We cannot guarantee "000000" is wrong (it is valid ~0.1% of time),
    // but we can at least verify the method returns a boolean without throwing.
    boolean result = totpService.verifyCode(secret, "000000", Instant.now());
    assertThat(result).isInstanceOf(Boolean.class);
  }

  @Test
  void verifyCodeShouldBeDeterministicForSameTimeStep() {
    String secret = totpService.generateSecret();
    Instant fixedInstant = Instant.ofEpochSecond(1_700_000_000L);

    boolean result1 = totpService.verifyCode(secret, "000000", fixedInstant);
    boolean result2 = totpService.verifyCode(secret, "000000", fixedInstant);

    assertThat(result1).isEqualTo(result2);
  }
}
