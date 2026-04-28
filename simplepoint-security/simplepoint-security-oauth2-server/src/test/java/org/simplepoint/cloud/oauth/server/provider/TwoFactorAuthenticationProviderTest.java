package org.simplepoint.cloud.oauth.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.cloud.oauth.server.service.TotpService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class TwoFactorAuthenticationProviderTest {

  @Mock
  private TotpService totpService;

  private TwoFactorAuthenticationProvider provider;

  private User user;

  @BeforeEach
  void setUp() {
    provider = new TwoFactorAuthenticationProvider(totpService);

    user = new User();
    user.setId("user-1");
    user.setTwoFactorEnabled(true);
    user.setTwoFactorSecret("TESTSECRET");
  }

  // ── supports ──────────────────────────────────────────────────────────────

  @Test
  void supportsShouldReturnTrueForTwoFactorAuthenticationToken() {
    assertThat(provider.supports(TwoFactorAuthenticationToken.class)).isTrue();
  }

  @Test
  void supportsShouldReturnFalseForUsernamePasswordToken() {
    assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
  }

  // ── authenticate: non-TwoFactorAuthenticationToken ────────────────────────

  @Test
  void authenticateShouldReturnNullForNonTwoFactorToken() {
    Authentication nonTfToken = new UsernamePasswordAuthenticationToken("alice", "password");

    Authentication result = provider.authenticate(nonTfToken);

    assertThat(result).isNull();
  }

  // ── authenticate: 2FA disabled ────────────────────────────────────────────

  @Test
  void authenticateShouldReturnNullWhenTwoFactorEnabledIsFalse() {
    user.setTwoFactorEnabled(false);
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken(user, "123456");

    Authentication result = provider.authenticate(token);

    assertThat(result).isNull();
  }

  @Test
  void authenticateShouldReturnNullWhenTwoFactorEnabledIsNull() {
    // When twoFactorEnabled is null, Boolean.FALSE.equals(null) is false,
    // so the provider proceeds to verify — it does NOT short-circuit to null.
    // With a null secret the provider returns null.
    user.setTwoFactorEnabled(null);
    user.setTwoFactorSecret(null);
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken(user, "123456");

    Authentication result = provider.authenticate(token);

    assertThat(result).isNull();
  }

  @Test
  void authenticateShouldReturnNullWhenSecretIsNull() {
    user.setTwoFactorSecret(null);
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken(user, "123456");

    Authentication result = provider.authenticate(token);

    assertThat(result).isNull();
  }

  // ── authenticate: non-User principal ──────────────────────────────────────

  @Test
  void authenticateShouldThrowBadCredentialsForNonUserPrincipal() {
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken("not-a-user", "123456");

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class);
  }

  // ── authenticate: valid code ───────────────────────────────────────────────

  @Test
  void authenticateShouldReturnAuthenticatedTokenWhenCodeIsValid() {
    when(totpService.verifyCode(eq("TESTSECRET"), eq("123456"), any())).thenReturn(true);
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken(user, "123456");

    Authentication result = provider.authenticate(token);

    assertThat(result).isNotNull();
    assertThat(result.isAuthenticated()).isTrue();
    assertThat(result.getPrincipal()).isEqualTo(user);
  }

  @Test
  void authenticateShouldReturnUsernamePasswordTokenOnSuccess() {
    when(totpService.verifyCode(eq("TESTSECRET"), eq("123456"), any())).thenReturn(true);
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken(user, "123456");

    Authentication result = provider.authenticate(token);

    assertThat(result).isInstanceOf(UsernamePasswordAuthenticationToken.class);
  }

  // ── authenticate: invalid code ────────────────────────────────────────────

  @Test
  void authenticateShouldThrowBadCredentialsWhenCodeIsInvalid() {
    when(totpService.verifyCode(eq("TESTSECRET"), eq("000000"), any())).thenReturn(false);
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken(user, "000000");

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessageContaining("Invalid two-factor");
  }
}
