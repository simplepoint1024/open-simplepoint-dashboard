package org.simplepoint.plugin.rbac.authentication;

import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Account-id budget prevents email/phone aliases from obtaining independent attempt budgets. */
public final class ProtectedPasswordAuthenticationProvider extends DaoAuthenticationProvider {
  private final LoginProtection protection;

  /** Uses the canonical account id to share limits across login aliases. */
  public ProtectedPasswordAuthenticationProvider(
      UserManagementService users, PasswordEncoder passwords, LoginProtection protection) {
    super(users);
    setPasswordEncoder(passwords);
    this.protection = protection;
  }

  @Override
  protected void additionalAuthenticationChecks(
      UserDetails details, UsernamePasswordAuthenticationToken authentication)
      throws AuthenticationException {
    String identity = details instanceof User user ? user.getId() : details.getUsername();
    protection.reserve("password-account", identity);
    if (details instanceof User user) {
      AuthenticationAccountState.requireActive(user);
    }
    super.additionalAuthenticationChecks(details, authentication);
    protection.success("password-account", identity);
  }
}
