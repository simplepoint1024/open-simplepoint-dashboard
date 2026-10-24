package org.simplepoint.core.security;

import java.util.Map;
import java.util.Optional;
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
    return clients != null
        && !clients.isEmpty()
        && authenticatedClientId(authentication).filter(clients::contains).isPresent();
  }

  /**
   * Returns the verified OAuth service identity with invocation scope. Authentication must have
   * been established by the platform security boundary. A display name or a user token carrying the
   * same scope does not establish service identity.
   */
  public static Optional<String> authenticatedClientId(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getAuthorities().stream()
            .noneMatch(
                authority -> "SCOPE_service-router.invoke".equals(authority.getAuthority()))) {
      return Optional.empty();
    }
    Map<String, Object> claims =
        authentication.getPrincipal() instanceof ClaimAccessor token
            ? token.getClaims()
            : authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal
                ? principal.getAttributes()
                : Map.of();
    if ("client_credentials".equals(claims.get("grant_type"))
        && claims.get("client_id") instanceof String client
        && !client.isBlank()) {
      return Optional.of(client);
    }
    return Optional.empty();
  }
}
