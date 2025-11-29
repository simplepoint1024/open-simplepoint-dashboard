package org.simplepoint.cloud.oauth.server.service;

import org.simplepoint.security.entity.User;

/**
 * Service interface for setting up two-factor authentication (2FA).
 */
public interface TwoFactorSetupService {

  /**
   * Enables two-factor authentication for the given user.
   *
   * @return an EnableResult containing the secret and otpauth URL
   */
  EnableResult enable(User user);

  /**
   * Disables two-factor authentication for the given user.
   */
  void disable(User user);

  /**
   * Result object for enabling 2FA, containing the secret and otpauth URL.
   */
  record EnableResult(String secret, String otpauthUrl) {
  }
}
