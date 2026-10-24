package org.simplepoint.plugin.rbac.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AccountCredentialVerifierTest {
  private final UserManagementService users = mock(UserManagementService.class);
  private final LoginProtection protection = mock(LoginProtection.class);
  private final TotpService totp = mock(TotpService.class);
  private final AuthenticationSecretCipher cipher = mock(AuthenticationSecretCipher.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final MfaVerificationService mfa =
      new MfaVerificationService(users, totp, protection, cipher);
  private final AccountCredentialVerifier verifier =
      new AccountCredentialVerifier(users, encoder, protection, mfa);
  private User passwordSnapshot;
  private User current;

  @BeforeEach
  void accounts() {
    passwordSnapshot = account();
    passwordSnapshot.setPassword(encoder.encode("correct-password"));
    current = account();
    when(users.loadUserByUsername("alice@example.test")).thenReturn(passwordSnapshot);
    when(users.findByIdForAuthorization("alice")).thenReturn(Optional.of(current));
  }

  @Test
  void passwordSuccessReturnsFreshAccountOnlyAfterBothSharedBudgets() {
    assertThat(verifier.verify("alice@example.test", "correct-password", null)).isSameAs(current);
    verify(protection).reservePasswordInput("alice@example.test");
    verify(protection).reserve("password-account", "alice");
    verify(protection).passwordInputSucceeded("alice@example.test");
    verifyNoInteractions(totp, cipher);
  }

  @Test
  void wrongOrUnknownPasswordCannotReachMfaOrResetInputBudget() {
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "wrong", "123456"))
        .isInstanceOf(BadCredentialsException.class);
    when(users.loadUserByUsername("unknown")).thenThrow(new UsernameNotFoundException("unknown"));
    assertThatThrownBy(() -> verifier.verify("unknown", "wrong", null))
        .isInstanceOf(BadCredentialsException.class);
    verify(users, never()).findByIdForAuthorization(anyString());
    verify(protection, never()).passwordInputSucceeded(anyString());
    verifyNoInteractions(totp, cipher);
  }

  @Test
  void changedVersionOrRemovedAccountBetweenFactorsIsRejected() {
    current.setAuthorizationVersion(2L);
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", null))
        .isInstanceOf(BadCredentialsException.class);
    when(users.findByIdForAuthorization("alice")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", null))
        .isInstanceOf(BadCredentialsException.class);
    verify(protection, never()).passwordInputSucceeded(anyString());
  }

  @Test
  void everyInactiveAccountStateIsRejectedAtPasswordAndCompletion() {
    List<Consumer<User>> restrictions =
        List.of(
            u -> u.setEnabled(false),
            u -> u.setAccountNonLocked(false),
            u -> u.setAccountNonExpired(false),
            u -> u.setCredentialsNonExpired(false),
            u -> u.setDeletedAt(Instant.now()),
            u -> u.setAuthorizationVersion(null));
    for (var restriction : restrictions) {
      User blocked = account();
      restriction.accept(blocked);
      when(users.findByIdForAuthorization("alice")).thenReturn(Optional.of(blocked));
      assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", null))
          .isInstanceOf(AuthenticationException.class);
      blocked.setPassword(passwordSnapshot.getPassword());
      when(users.loadUserByUsername("alice@example.test")).thenReturn(blocked);
      assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", null))
          .isInstanceOf(AuthenticationException.class);
      when(users.loadUserByUsername("alice@example.test")).thenReturn(passwordSnapshot);
    }
    verify(protection, never()).passwordInputSucceeded(anyString());
  }

  @Test
  void configuredMfaCannotBeSkippedAndUsesSharedReplayGuard() {
    current.setTwoFactorEnabled(true);
    current.setTwoFactorSecret("ciphertext");
    when(cipher.decrypt("alice", "ciphertext")).thenReturn("seed");
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", null))
        .isInstanceOf(BadCredentialsException.class);
    when(totp.matchingStep(eq("seed"), eq("123456"), any())).thenReturn(456L);
    when(protection.consumeTotp("alice", "ciphertext", 456)).thenReturn(true, false);
    when(protection.completeChallenge(anyString())).thenReturn(true);
    assertThat(verifier.verify("alice@example.test", "correct-password", "123456"))
        .isSameAs(current);
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", "123456"))
        .isInstanceOf(BadCredentialsException.class);
    verify(protection).passwordInputSucceeded("alice@example.test");
  }

  @Test
  void throttledInputDoesNotLoadAnAccount() {
    org.mockito.Mockito.doThrow(new LockedException("locked"))
        .when(protection)
        .reservePasswordInput("alice@example.test");
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "correct-password", null))
        .isInstanceOf(LockedException.class);
    verify(users, never()).loadUserByUsername(anyString());
  }

  @Test
  void oversizedSecretsNeverReachPasswordHashing() {
    assertThatThrownBy(() -> verifier.verify("alice@example.test", "p".repeat(4097), null))
        .isInstanceOf(BadCredentialsException.class);
    assertThatThrownBy(
            () -> verifier.verify("alice@example.test", "correct-password", "1".repeat(17)))
        .isInstanceOf(BadCredentialsException.class);
    verify(users, never()).loadUserByUsername(anyString());
  }

  private User account() {
    User user = new User();
    user.setId("alice");
    user.setAuthorizationVersion(1L);
    return user;
  }
}
