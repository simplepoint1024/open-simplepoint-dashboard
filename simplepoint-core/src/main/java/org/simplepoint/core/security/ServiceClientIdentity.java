package org.simplepoint.core.security;

import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * Uses signed OAuth client/grant claims; a user with the same name as a service is never a service.
 */
public final class ServiceClientIdentity {
  private ServiceClientIdentity() {}

  /** For narrow contracts which accept another service's effective end-user context. */
  public static boolean allowed(Authentication authentication, Set<String> clients) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || clients == null
        || clients.isEmpty()
        || authentication.getAuthorities().stream()
            .noneMatch(
                authority -> "SCOPE_service-router.invoke".equals(authority.getAuthority()))) {
      return false;
    }
    Map<String, Object> claims =
        authentication.getPrincipal() instanceof ClaimAccessor token
            ? token.getClaims()
            : authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal
                ? principal.getAttributes()
                : Map.of();
    return "client_credentials".equals(claims.get("grant_type"))
        && claims.get("client_id") instanceof String client
        && clients.contains(client);
  }
}
