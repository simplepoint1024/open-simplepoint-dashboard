package org.simplepoint.plugin.rbac.authentication;

import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.BadCredentialsException;

/** Common account policy for password checks and completion of subsequent factors. */
public final class AuthenticationAccountState {
  private AuthenticationAccountState() {}

  /** Applies the same local account policy during factor completion and session revalidation. */
  public static User requireActive(User user) {
    if (user == null
        || user.getId() == null
        || user.getId().isBlank()
        || !user.isEnabled()
        || !user.isAccountNonLocked()
        || !user.isAccountNonExpired()
        || !user.isCredentialsNonExpired()
        || user.getDeletedAt() != null
        || user.getAuthorizationVersion() == null
        || user.getAuthorizationVersion() < 0) {
      throw new BadCredentialsException("Invalid credentials");
    }
    return user;
  }
}
