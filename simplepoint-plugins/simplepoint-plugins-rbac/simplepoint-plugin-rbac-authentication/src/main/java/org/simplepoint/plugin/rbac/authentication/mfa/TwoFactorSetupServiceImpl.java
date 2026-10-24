package org.simplepoint.plugin.rbac.authentication.mfa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.simplepoint.plugin.rbac.authentication.TotpService;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enrollment is serialized per account and can never replace an active second factor. */
@Service
@Transactional
public class TwoFactorSetupServiceImpl implements TwoFactorSetupService {
  private final TotpService totp;
  private final EntityManager em;
  private final AuthenticationSecretCipher cipher;
  private final LoginProtection protection;
  private final PasswordEncoder passwords;

  /** Configures serialized account updates and shared verification controls. */
  public TwoFactorSetupServiceImpl(
      TotpService totp,
      EntityManager em,
      AuthenticationSecretCipher cipher,
      LoginProtection protection,
      PasswordEncoder passwords) {
    this.totp = totp;
    this.em = em;
    this.cipher = cipher;
    this.protection = protection;
    this.passwords = passwords;
  }

  @Override
  public EnableResult enable(User principal) {
    User user = current(principal);
    if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
      throw invalid();
    }
    if (pending(user)) {
      return result(user);
    }
    user.setTwoFactorSecret(cipher.encrypt(user.getId(), totp.generateSecret()));
    user.setTwoFactorEnabled(false);
    user.setTwoFactorPendingUntil(Instant.now().plusSeconds(300));
    return result(user);
  }

  @Override
  public boolean confirm(User principal, String code) {
    User user = current(principal);
    protection.reserve("totp", user.getId());
    if (!pending(user)) {
      return false;
    }
    Long step =
        totp.matchingStep(
            cipher.decrypt(user.getId(), user.getTwoFactorSecret()), code, Instant.now());
    if (step == null || !protection.consumeTotp(user.getId(), user.getTwoFactorSecret(), step)) {
      return false;
    }
    user.setTwoFactorEnabled(true);
    user.setTwoFactorPendingUntil(null);
    user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);
    protection.success("totp", user.getId());
    return true;
  }

  @Override
  public EnableResult currentPending(User principal) {
    User user = current(principal);
    return pending(user) ? result(user) : null;
  }

  @Override
  public void disable(User principal, String password, String code) {
    User user = current(principal);
    protection.reserve("totp", user.getId());
    if (!Boolean.TRUE.equals(user.getTwoFactorEnabled())
        || password == null
        || !passwords.matches(password, user.getPassword())) {
      throw invalid();
    }
    Long step =
        totp.matchingStep(
            cipher.decrypt(user.getId(), user.getTwoFactorSecret()), code, Instant.now());
    if (step == null || !protection.consumeTotp(user.getId(), user.getTwoFactorSecret(), step)) {
      throw invalid();
    }
    user.setTwoFactorEnabled(false);
    user.setTwoFactorSecret(null);
    user.setTwoFactorPendingUntil(null);
    user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);
    protection.success("totp", user.getId());
  }

  private User current(User principal) {
    User user = em.find(User.class, principal.getId(), LockModeType.PESSIMISTIC_WRITE);
    if (user == null
        || user.getDeletedAt() != null
        || !user.isEnabled()
        || !user.isAccountNonLocked()
        || !user.isAccountNonExpired()
        || !user.isCredentialsNonExpired()) {
      throw invalid();
    }
    return user;
  }

  private boolean pending(User user) {
    return !Boolean.TRUE.equals(user.getTwoFactorEnabled())
        && user.getTwoFactorSecret() != null
        && user.getTwoFactorPendingUntil() != null
        && Instant.now().isBefore(user.getTwoFactorPendingUntil());
  }

  private EnableResult result(User user) {
    String secret = cipher.decrypt(user.getId(), user.getTwoFactorSecret());
    return new EnableResult(
        secret, totp.buildOtpAuthUrl("Simplepoint", user.getUsername(), secret));
  }

  private static BadCredentialsException invalid() {
    return new BadCredentialsException("Account security verification failed");
  }
}
