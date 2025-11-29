package org.simplepoint.cloud.oauth.server.provider;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication token representing a two-factor (TOTP) verification attempt.
 */
public class TwoFactorAuthenticationToken extends AbstractAuthenticationToken {

  private final Object principal;
  private final String verificationCode;

  /**
   * Creates an unauthenticated TwoFactorAuthenticationToken.
   *
   * @param principal        the principal (e.g., username or UserDetails)
   * @param verificationCode the submitted TOTP code
   */
  public TwoFactorAuthenticationToken(Object principal, String verificationCode) {
    super(null);
    this.principal = principal;
    this.verificationCode = verificationCode;
    setAuthenticated(false);
  }

  /**
   * Creates an authenticated TwoFactorAuthenticationToken with authorities.
   */
  public TwoFactorAuthenticationToken(Object principal, String verificationCode,
      Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.principal = principal;
    this.verificationCode = verificationCode;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return this.verificationCode;
  }

  @Override
  public Object getPrincipal() {
    return this.principal;
  }
}
