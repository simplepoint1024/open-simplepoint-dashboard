package org.simplepoint.plugin.rbac.authentication.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.simplepoint.plugin.rbac.authentication.TotpService;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

class TwoFactorSetupServiceImplTest {
  EntityManager em = mock(EntityManager.class);
  TotpService totp = mock(TotpService.class);
  AuthenticationSecretCipher cipher = mock(AuthenticationSecretCipher.class);
  LoginProtection protection = mock(LoginProtection.class);
  PasswordEncoder passwords = mock(PasswordEncoder.class);
  TwoFactorSetupServiceImpl service =
      new TwoFactorSetupServiceImpl(totp, em, cipher, protection, passwords);
  User user;

  @BeforeEach
  void setup() {
    user = new User();
    user.setId("alice");
    user.setTwoFactorEnabled(false);
    when(em.find(User.class, "alice", LockModeType.PESSIMISTIC_WRITE)).thenReturn(user);
    when(totp.generateSecret()).thenReturn("SEED");
    when(cipher.encrypt("alice", "SEED")).thenReturn("ciphertext");
    when(cipher.decrypt("alice", "ciphertext")).thenReturn("SEED");
  }

  @Test
  void enrollmentPersistsOnlyCiphertextWithExpiryAndCannotResetActiveFactor() {
    assertThat(service.enable(user).secret()).isEqualTo("SEED");
    assertThat(user.getTwoFactorSecret()).isEqualTo("ciphertext");
    assertThat(user.getTwoFactorPendingUntil()).isAfter(Instant.now());
    service.enable(user);
    verify(totp, times(1)).generateSecret();
    user.setTwoFactorEnabled(true);
    assertThatThrownBy(() -> service.enable(user)).isInstanceOf(BadCredentialsException.class);
    assertThat(service.currentPending(user)).isNull();
  }

  @Test
  void confirmationConsumesCodeAndBumpsAuthorizationVersion() {
    service.enable(user);
    when(totp.matchingStep(eq("SEED"), eq("123456"), any())).thenReturn(123L);
    when(protection.consumeTotp("alice", "ciphertext", 123)).thenReturn(true);
    assertThat(service.confirm(user, "123456")).isTrue();
    assertThat(user.getTwoFactorEnabled()).isTrue();
    assertThat(user.getTwoFactorPendingUntil()).isNull();
    assertThat(user.getAuthorizationVersion()).isEqualTo(1);
    assertThat(service.currentPending(user)).isNull();
  }

  @Test
  void expiredEnrollmentAndReplayedCodeDoNotEnableFactor() {
    service.enable(user);
    user.setTwoFactorPendingUntil(Instant.EPOCH);
    assertThat(service.confirm(user, "123456")).isFalse();
    verify(totp, never()).matchingStep(any(), any(), any());
    user.setTwoFactorPendingUntil(Instant.now().plusSeconds(60));
    when(totp.matchingStep(any(), any(), any())).thenReturn(123L);
    assertThat(service.confirm(user, "123456")).isFalse();
    assertThat(user.getTwoFactorEnabled()).isFalse();
  }

  @Test
  void disableRequiresCurrentPasswordAndFreshCode() {
    service.enable(user);
    user.setTwoFactorEnabled(true);
    user.setPassword("hash");
    assertThatThrownBy(() -> service.disable(user, "wrong", "123456"))
        .isInstanceOf(BadCredentialsException.class);
    assertThat(user.getTwoFactorEnabled()).isTrue();
    when(passwords.matches("correct", "hash")).thenReturn(true);
    when(totp.matchingStep(eq("SEED"), eq("123456"), any())).thenReturn(123L);
    when(protection.consumeTotp("alice", "ciphertext", 123)).thenReturn(true);
    service.disable(user, "correct", "123456");
    assertThat(user.getTwoFactorEnabled()).isFalse();
    assertThat(user.getTwoFactorSecret()).isNull();
  }

  @Test
  void deletedUserCannotBeResurrectedFromSession() {
    when(em.find(User.class, "alice", LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);
    assertThatThrownBy(() -> service.enable(user)).isInstanceOf(BadCredentialsException.class);
  }
}
