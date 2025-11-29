package org.simplepoint.cloud.oauth.server.service;

import java.time.Instant;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.security.entity.User;
import org.springframework.stereotype.Service;

/**
 * Implementation of the TwoFactorSetupService for managing TOTP-based
 * two-factor authentication setup for users.
 */
@Service
public class TwoFactorSetupServiceImpl implements TwoFactorSetupService {
  private final TotpService totpService;

  private final UserRepository userRepository;

  /**
   * Constructs a TwoFactorSetupServiceImpl with the specified TotpService
   * and UserRepository.
   *
   * @param totpService    the TOTP service for generating secrets and URLs
   * @param userRepository the user repository for persisting user data
   */
  public TwoFactorSetupServiceImpl(TotpService totpService, UserRepository userRepository) {
    this.totpService = totpService;
    this.userRepository = userRepository;
  }

  @Override
  public EnableResult enable(User user) {
    // Reload user from repository to ensure we have a managed entity
    User persisted = userRepository.findById(user.getId()).orElse(user);

    String secret = totpService.generateSecret();
    // 这里只是为当前用户准备一个待确认的 secret，不立即启用 2FA
    persisted.setTwoFactorSecret(secret);
    persisted.setTwoFactorEnabled(Boolean.FALSE);
    user.setTwoFactorSecret(secret);
    user.setTwoFactorEnabled(Boolean.FALSE);
    userRepository.save(persisted);
    return new EnableResult(secret,
        totpService.buildOtpAuthUrl("Simplepoint", persisted.getUsername(), secret));
  }

  @Override
  public boolean confirm(User user, String code) {
    User persisted = userRepository.findById(user.getId()).orElse(user);
    String secret = persisted.getTwoFactorSecret();
    if (secret == null || secret.isEmpty()) {
      return false;
    }
    boolean ok = totpService.verifyCode(secret, code, Instant.now());
    if (!ok) {
      return false;
    }
    // 校验通过，真正启用 2FA
    persisted.setTwoFactorEnabled(Boolean.TRUE);
    user.setTwoFactorEnabled(Boolean.TRUE);
    userRepository.save(persisted);
    return true;
  }

  @Override
  public EnableResult currentPending(User user) {
    User persisted = userRepository.findById(user.getId()).orElse(user);
    String secret = persisted.getTwoFactorSecret();
    if (secret == null || secret.isEmpty()) {
      return null;
    }
    return new EnableResult(secret,
        totpService.buildOtpAuthUrl("Simplepoint", persisted.getUsername(), secret));
  }

  @Override
  public void disable(User user) {
    User persisted = userRepository.findById(user.getId()).orElse(user);
    persisted.setTwoFactorEnabled(false);
    persisted.setTwoFactorSecret(null);
    user.setTwoFactorEnabled(false);
    user.setTwoFactorSecret(null);
    userRepository.save(persisted);
  }
}
