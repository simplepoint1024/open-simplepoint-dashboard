package org.simplepoint.cloud.oauth.server.service;

import org.simplepoint.security.entity.User;

/**
 * Service interface for setting up two-factor authentication (2FA).
 */
public interface TwoFactorSetupService {

  /**
   * Prepare enabling two-factor authentication for the given user.
   * This generates a secret and otpauth URL but does not mark 2FA as enabled
   * until it is confirmed with a valid TOTP code.
   *
   * @return an EnableResult containing the secret and otpauth URL
   */
  EnableResult enable(User user);

  /**
   * Confirm enabling 2FA by verifying the first TOTP code for the pending secret.
   *
   * @param user the current user
   * @param code the TOTP code from authenticator app
   * @return true if confirmation succeeded and 2FA is now enabled
   */
  boolean confirm(User user, String code);

  /**
   * Get the current pending 2FA setup (secret + otpauth URL) if any.
   */
  EnableResult currentPending(User user);

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
