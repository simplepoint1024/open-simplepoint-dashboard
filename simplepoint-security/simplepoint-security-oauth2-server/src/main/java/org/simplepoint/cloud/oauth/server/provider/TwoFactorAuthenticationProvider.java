package org.simplepoint.cloud.oauth.server.provider;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.service.TotpService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Two-Factor Authentication Provider.
 * 双因素认证提供者
 */
@Slf4j
@RequiredArgsConstructor
public class TwoFactorAuthenticationProvider implements AuthenticationProvider {

  private final TotpService totpService;

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    if (!(authentication instanceof TwoFactorAuthenticationToken token)) {
      return null;
    }

    Object principal = token.getPrincipal();
    String code = (String) token.getCredentials();

    if (!(principal instanceof User user)) {
      throw new BadCredentialsException("Unsupported principal for 2FA");
    }

    // If user has not enabled 2FA or has no secret, this provider does not handle it.
    if (Boolean.FALSE.equals(user.getTwoFactorEnabled()) || user.getTwoFactorSecret() == null) {
      return null;
    }

    String secret = user.getTwoFactorSecret();
    boolean ok = totpService.verifyCode(secret, code, Instant.now());
    log.debug("2FA verify for user [{}], secret=[{}], code=[{}] -> {}", user.getUsername(), secret, code, ok);
    if (!ok) {
      throw new BadCredentialsException("Invalid two-factor authentication code");
    }

    // On success, return a fully authenticated UsernamePasswordAuthenticationToken
    return new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return TwoFactorAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
