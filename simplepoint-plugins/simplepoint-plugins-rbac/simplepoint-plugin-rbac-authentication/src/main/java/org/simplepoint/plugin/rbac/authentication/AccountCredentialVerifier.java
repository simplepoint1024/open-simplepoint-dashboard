package org.simplepoint.plugin.rbac.authentication;

import java.time.Instant;
import java.util.UUID;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Local complete credential verification, shared by non-browser authentication adapters.
 *
 * <p>The caller must durably audit the outcome before exposing a session. Returned entities remain
 * inside the identity domain; transports must project a credential-free result.
 */
@Service
public class AccountCredentialVerifier {
  private final UserManagementService users;
  private final ProtectedPasswordAuthenticationProvider passwords;
  private final LoginProtection protection;
  private final MfaVerificationService mfa;

  /** Reuses the same password/account budget and MFA implementation as browser login. */
  public AccountCredentialVerifier(
      UserManagementService users,
      PasswordEncoder encoder,
      LoginProtection protection,
      MfaVerificationService mfa) {
    this.users = users;
    this.passwords = new ProtectedPasswordAuthenticationProvider(users, encoder, protection);
    this.protection = protection;
    this.mfa = mfa;
  }

  /** Verifies every configured factor and reloads account policy after the password check. */
  public User verify(String username, String password, String oneTimeCode) {
    protection.reservePasswordInput(username);
    if (password == null
        || password.isEmpty()
        || password.length() > 4096
        || (oneTimeCode != null && oneTimeCode.length() > 16)) {
      throw invalid();
    }
    var token = UsernamePasswordAuthenticationToken.unauthenticated(username, password);
    User firstFactor;
    try {
      var authenticated = passwords.authenticate(token);
      if (!(authenticated.getPrincipal() instanceof User user)) {
        throw invalid();
      }
      firstFactor = user;
    } finally {
      token.eraseCredentials();
    }
    long authenticatedVersion = firstFactor.getAuthorizationVersion();
    var current =
        AuthenticationAccountState.requireActive(
            users
                .findByIdForAuthorization(firstFactor.getId())
                .orElseThrow(AccountCredentialVerifier::invalid));
    if (current.getAuthorizationVersion() != authenticatedVersion) {
      throw invalid();
    }
    if (Boolean.TRUE.equals(current.getTwoFactorEnabled())) {
      current =
          mfa.verify(
              new MfaChallenge(
                  current.getId(), current.getAuthorizationVersion(),
                  Instant.now().plusSeconds(300), UUID.randomUUID().toString()),
              oneTimeCode);
    }
    protection.passwordInputSucceeded(username);
    return current;
  }

  private static BadCredentialsException invalid() {
    return new BadCredentialsException("Invalid credentials");
  }
}
