package org.simplepoint.plugin.rbac.authentication;

import java.time.Instant;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/**
 * Reloads account policy at completion; neither an old session nor a stale user can grant access.
 */
@Service
public class MfaVerificationService {
  private final UserManagementService users;
  private final TotpService totp;
  private final LoginProtection protection;
  private final AuthenticationSecretCipher cipher;

  /** Combines fresh account policy, seed decryption and shared replay protection. */
  public MfaVerificationService(
      UserManagementService users,
      TotpService totp,
      LoginProtection protection,
      AuthenticationSecretCipher cipher) {
    this.users = users;
    this.totp = totp;
    this.protection = protection;
    this.cipher = cipher;
  }

  /** Verifies the current challenge without granting access on failure. */
  public User verify(MfaChallenge pending, String code) {
    if (pending.expired()) {
      throw invalid();
    }
    String userId = pending.userId();
    protection.reserve("totp", userId);
    User user =
        AuthenticationAccountState.requireActive(
            users.findByIdForAuthorization(userId).orElseThrow(MfaVerificationService::invalid));
    if (!Boolean.TRUE.equals(user.getTwoFactorEnabled())
        || user.getTwoFactorSecret() == null
        || user.getAuthorizationVersion() == null
        || user.getAuthorizationVersion() != pending.authorizationVersion()) {
      throw invalid();
    }
    Long step =
        totp.matchingStep(cipher.decrypt(userId, user.getTwoFactorSecret()), code, Instant.now());
    if (step == null || !protection.consumeTotp(userId, user.getTwoFactorSecret(), step)) {
      throw invalid();
    }
    if (!protection.completeChallenge(pending.challengeId())) {
      throw invalid();
    }
    protection.success("totp", userId);
    return user;
  }

  private static BadCredentialsException invalid() {
    return new BadCredentialsException("Invalid authentication code");
  }
}
