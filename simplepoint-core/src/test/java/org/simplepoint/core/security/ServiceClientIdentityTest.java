package org.simplepoint.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class ServiceClientIdentityTest {
  @Test
  void acceptsSignedClientCredentialsClaimsWithInvocationScope() {
    var claims = Map.<String, Object>of("client_id", "app", "grant_type", "client_credentials");
    for (Object principal : List.of(new DefaultOAuth2AuthenticatedPrincipal("irrelevant", claims, List.of()),
        (ClaimAccessor) () -> claims)) {
      assertThat(ServiceClientIdentity.allowed(new TestingAuthenticationToken(principal, null,
          "SCOPE_service-router.invoke"), Set.of("app"))).isTrue();
    }
  }

  @Test
  void rejectsNameCollisionsOtherGrantsMissingScopeAndUnconfiguredClients() {
    assertThat(ServiceClientIdentity.allowed(new TestingAuthenticationToken("app", null,
        "SCOPE_service-router.invoke"), Set.of("app"))).isFalse();
    for (Map<String, Object> claims : List.of(
        Map.<String, Object>of("sub", "app", "client_id", "browser", "grant_type", "authorization_code"),
        Map.<String, Object>of("client_id", "app", "grant_type", "authorization_code"),
        Map.<String, Object>of("client_id", "other", "grant_type", "client_credentials"),
        Map.<String, Object>of("client_id", "app"))) {
      var principal = new DefaultOAuth2AuthenticatedPrincipal("app", claims, List.of());
      assertThat(ServiceClientIdentity.allowed(new TestingAuthenticationToken(principal, null,
          "SCOPE_service-router.invoke"), Set.of("app"))).isFalse();
    }
    var principal = new DefaultOAuth2AuthenticatedPrincipal("app", Map.of("client_id", "app", "grant_type", "client_credentials"), List.of());
    assertThat(ServiceClientIdentity.allowed(new TestingAuthenticationToken(principal, null, "ROLE_ADMIN"), Set.of("app"))).isFalse();
    assertThat(ServiceClientIdentity.allowed(new TestingAuthenticationToken(principal, null, "SCOPE_service-router.invoke"), Set.of())).isFalse();
  }
}
