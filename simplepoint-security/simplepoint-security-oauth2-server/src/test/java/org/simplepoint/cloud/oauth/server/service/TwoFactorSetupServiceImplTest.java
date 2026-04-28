package org.simplepoint.cloud.oauth.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.cloud.oauth.server.service.TwoFactorSetupService.EnableResult;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.security.entity.User;

@ExtendWith(MockitoExtension.class)
class TwoFactorSetupServiceImplTest {

  @Mock
  private TotpService totpService;

  @Mock
  private UserRepository userRepository;

  private TwoFactorSetupServiceImpl service;

  private User user;
  private User persisted;

  @BeforeEach
  void setUp() {
    service = new TwoFactorSetupServiceImpl(totpService, userRepository);

    user = new User();
    user.setId("user-1");
    user.setUsername("alice");

    persisted = new User();
    persisted.setId("user-1");
    persisted.setUsername("alice");
  }

  // ── enable ────────────────────────────────────────────────────────────────

  @Test
  void enableShouldGenerateSecretAndReturnEnableResult() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));
    when(totpService.generateSecret()).thenReturn("TESTSECRET");
    when(totpService.buildOtpAuthUrl(anyString(), anyString(), eq("TESTSECRET")))
        .thenReturn("otpauth://totp/Simplepoint:alice?secret=TESTSECRET&issuer=Simplepoint");

    EnableResult result = service.enable(user);

    assertThat(result).isNotNull();
    assertThat(result.secret()).isEqualTo("TESTSECRET");
    assertThat(result.otpauthUrl()).contains("TESTSECRET");
  }

  @Test
  void enableShouldMarkUserTwoFactorEnabledFalse() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));
    when(totpService.generateSecret()).thenReturn("TESTSECRET");
    when(totpService.buildOtpAuthUrl(anyString(), anyString(), anyString()))
        .thenReturn("otpauth://...");

    service.enable(user);

    assertThat(persisted.getTwoFactorEnabled()).isFalse();
    assertThat(persisted.getTwoFactorSecret()).isEqualTo("TESTSECRET");
    assertThat(user.getTwoFactorEnabled()).isFalse();
    assertThat(user.getTwoFactorSecret()).isEqualTo("TESTSECRET");
  }

  @Test
  void enableShouldPersistUser() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));
    when(totpService.generateSecret()).thenReturn("TESTSECRET");
    when(totpService.buildOtpAuthUrl(anyString(), anyString(), anyString()))
        .thenReturn("otpauth://...");

    service.enable(user);

    verify(userRepository).save(persisted);
  }

  @Test
  void enableShouldFallBackToPassedUserWhenNotFoundInRepository() {
    when(userRepository.findById("user-1")).thenReturn(Optional.empty());
    when(totpService.generateSecret()).thenReturn("TESTSECRET");
    when(totpService.buildOtpAuthUrl(anyString(), anyString(), anyString()))
        .thenReturn("otpauth://...");

    EnableResult result = service.enable(user);

    assertThat(result).isNotNull();
    verify(userRepository).save(user);
  }

  // ── confirm ───────────────────────────────────────────────────────────────

  @Test
  void confirmShouldReturnTrueAndEnableWhenCodeIsValid() {
    persisted.setTwoFactorSecret("TESTSECRET");
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));
    when(totpService.verifyCode(eq("TESTSECRET"), eq("123456"), any())).thenReturn(true);

    boolean result = service.confirm(user, "123456");

    assertThat(result).isTrue();
    assertThat(persisted.getTwoFactorEnabled()).isTrue();
    verify(userRepository).save(persisted);
  }

  @Test
  void confirmShouldReturnFalseWhenCodeIsInvalid() {
    persisted.setTwoFactorSecret("TESTSECRET");
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));
    when(totpService.verifyCode(eq("TESTSECRET"), eq("000000"), any())).thenReturn(false);

    boolean result = service.confirm(user, "000000");

    assertThat(result).isFalse();
    verify(userRepository, never()).save(any());
  }

  @Test
  void confirmShouldReturnFalseWhenSecretIsNull() {
    persisted.setTwoFactorSecret(null);
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));

    boolean result = service.confirm(user, "123456");

    assertThat(result).isFalse();
    verify(totpService, never()).verifyCode(any(), any(), any());
  }

  @Test
  void confirmShouldReturnFalseWhenSecretIsEmpty() {
    persisted.setTwoFactorSecret("");
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));

    boolean result = service.confirm(user, "123456");

    assertThat(result).isFalse();
    verify(totpService, never()).verifyCode(any(), any(), any());
  }

  // ── currentPending ────────────────────────────────────────────────────────

  @Test
  void currentPendingShouldReturnEnableResultWhenSecretIsPresent() {
    persisted.setTwoFactorSecret("TESTSECRET");
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));
    when(totpService.buildOtpAuthUrl(anyString(), anyString(), eq("TESTSECRET")))
        .thenReturn("otpauth://...");

    EnableResult result = service.currentPending(user);

    assertThat(result).isNotNull();
    assertThat(result.secret()).isEqualTo("TESTSECRET");
  }

  @Test
  void currentPendingShouldReturnNullWhenNoSecret() {
    persisted.setTwoFactorSecret(null);
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));

    EnableResult result = service.currentPending(user);

    assertThat(result).isNull();
  }

  @Test
  void currentPendingShouldReturnNullWhenSecretIsEmpty() {
    persisted.setTwoFactorSecret("");
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));

    EnableResult result = service.currentPending(user);

    assertThat(result).isNull();
  }

  // ── disable ───────────────────────────────────────────────────────────────

  @Test
  void disableShouldClearSecretAndSetEnabledFalse() {
    persisted.setTwoFactorSecret("TESTSECRET");
    persisted.setTwoFactorEnabled(true);
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));

    service.disable(user);

    assertThat(persisted.getTwoFactorEnabled()).isFalse();
    assertThat(persisted.getTwoFactorSecret()).isNull();
    assertThat(user.getTwoFactorEnabled()).isFalse();
    assertThat(user.getTwoFactorSecret()).isNull();
  }

  @Test
  void disableShouldPersistUser() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(persisted));

    service.disable(user);

    verify(userRepository).save(persisted);
  }
}
