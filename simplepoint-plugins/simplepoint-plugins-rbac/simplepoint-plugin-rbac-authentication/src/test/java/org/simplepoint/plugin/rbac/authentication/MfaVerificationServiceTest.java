package org.simplepoint.plugin.rbac.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;

class MfaVerificationServiceTest {
  UserManagementService users = mock(UserManagementService.class);
  TotpService totp = mock(TotpService.class);
  LoginProtection protection = mock(LoginProtection.class);
  AuthenticationSecretCipher cipher = mock(AuthenticationSecretCipher.class);
  MfaVerificationService service = new MfaVerificationService(users, totp, protection, cipher);
  MfaChallenge pending = new MfaChallenge("alice", 1, Instant.now().plusSeconds(60), "challenge-1");

  User user() {
    User user = new User();
    user.setId("alice");
    user.setAuthorizationVersion(1L);
    user.setTwoFactorEnabled(true);
    user.setTwoFactorSecret("encrypted");
    when(users.findByIdForAuthorization("alice")).thenReturn(Optional.of(user));
    return user;
  }

  @Test
  void removedDisabledOrChangedAccountCannotCompleteAnOldChallenge() {
    User user = user();
    user.setEnabled(false);
    assertThatThrownBy(() -> service.verify(pending, "123456"))
        .isInstanceOf(BadCredentialsException.class);
    user.setEnabled(true);
    user.setAuthorizationVersion(2L);
    assertThatThrownBy(() -> service.verify(pending, "123456"))
        .isInstanceOf(BadCredentialsException.class);
    user.setAuthorizationVersion(1L);
    user.setTwoFactorEnabled(false);
    assertThatThrownBy(() -> service.verify(pending, "123456"))
        .isInstanceOf(BadCredentialsException.class);
    verifyNoInteractions(totp, cipher);
  }

  @Test
  void validAndFreshCodeIsRequiredAndNoCredentialsAreLogged() {
    User user = user();
    when(cipher.decrypt("alice", "encrypted")).thenReturn("secret");
    when(totp.matchingStep(eq("secret"), eq("123456"), any())).thenReturn(123L);
    assertThatThrownBy(() -> service.verify(pending, "123456"))
        .isInstanceOf(BadCredentialsException.class);
    verify(protection, never()).success(any(), any());
    when(protection.consumeTotp("alice", "encrypted", 123)).thenReturn(true);
    when(protection.completeChallenge(pending.challengeId())).thenReturn(true);
    assertThat(service.verify(pending, "123456")).isSameAs(user);
    verify(protection).success("totp", "alice");
  }
}
