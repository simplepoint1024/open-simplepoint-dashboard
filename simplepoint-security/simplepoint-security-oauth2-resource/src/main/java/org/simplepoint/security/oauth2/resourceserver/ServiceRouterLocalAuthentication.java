package org.simplepoint.security.oauth2.resourceserver;

import com.simplepoint.service.router.invocation.LocalInvocationContext;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.StringUtils;

/**
 * Authenticates the host's outbound credential using the resource server's JWT and revocation
 * policy. Browser authentication and submitted identity claims are never used as credentials.
 */
public final class ServiceRouterLocalAuthentication implements LocalInvocationContext {
  private final ServiceRouterAccessTokenProvider tokens;
  private final JwtDecoder decoder;
  private final JwtAuthenticationConverterDelegate converter;

  /** Uses the same token source and validation policy as a remote service call. */
  public ServiceRouterLocalAuthentication(
      ServiceRouterAccessTokenProvider tokens,
      JwtDecoder decoder,
      JwtAuthenticationConverterDelegate converter) {
    this.tokens = tokens;
    this.decoder = decoder;
    this.converter = converter;
  }

  @Override
  public Scope open() {
    Authentication authentication = authenticate();
    if (ServiceClientIdentity.authenticatedClientId(authentication).isEmpty()) {
      throw new AccessDeniedException("Local service invocation requires a service credential");
    }
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    return () -> SecurityContextHolder.setContext(previous);
  }

  private Authentication authenticate() {
    String token = tokens.getAccessToken();
    if (!StringUtils.hasText(token)) {
      throw new AccessDeniedException("Local service invocation has no service credential");
    }
    try {
      return converter.convert(decoder.decode(token));
    } catch (JwtException | OAuth2AuthenticationException rejected) {
      // Refresh only before provider invocation. Business work is never replayed.
      tokens.invalidate(token);
      String refreshed = tokens.getAccessToken();
      if (!StringUtils.hasText(refreshed)) {
        throw new AccessDeniedException("Local service invocation has no service credential");
      }
      return converter.convert(decoder.decode(refreshed));
    }
  }
}
