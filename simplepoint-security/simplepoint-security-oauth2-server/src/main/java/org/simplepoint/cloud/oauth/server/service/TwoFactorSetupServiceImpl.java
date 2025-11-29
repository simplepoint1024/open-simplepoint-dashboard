package org.simplepoint.cloud.oauth.server.service;

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
    persisted.setTwoFactorSecret(secret);
    persisted.setTwoFactorEnabled(true);
    user.setTwoFactorSecret(secret);
    user.setTwoFactorEnabled(true);
    userRepository.save(persisted);
    return new EnableResult(secret, totpService.buildOtpAuthUrl("Simplepoint", persisted.getUsername(), persisted.getTwoFactorSecret()));
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
