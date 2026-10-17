package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.service.properties.ManagedTenantResourceProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ManagedTenantResourceCallerGuardTest {

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void mapsAuthenticatedServiceIdentityToOwnedNamespace() {
    ManagedTenantResourceProperties properties = new ManagedTenantResourceProperties();
    properties.setCallerOwners(Map.of("simplepoint-service-app", "app"));
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
                "simplepoint-service-app", Map.of("client_id", "simplepoint-service-app",
                    "grant_type", "client_credentials"), java.util.List.of()),
            "ignored",
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_service-router.invoke"))
        )
    );

    assertThat(new ManagedTenantResourceCallerGuard(properties).requireOwner()).isEqualTo("app");
  }

  @Test
  void ordinaryUserCannotImpersonateConfiguredServiceByName() {
    ManagedTenantResourceProperties properties = new ManagedTenantResourceProperties();
    properties.setCallerOwners(Map.of("simplepoint-service-app", "app"));
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("simplepoint-service-app", "ignored", java.util.List.of()));
    assertThatThrownBy(() -> new ManagedTenantResourceCallerGuard(properties).requireOwner())
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void failsClosedWhenNoCallerMappingExists() {
    ManagedTenantResourceProperties properties = new ManagedTenantResourceProperties();
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("unknown", "ignored", java.util.List.of())
    );

    assertThatThrownBy(() -> new ManagedTenantResourceCallerGuard(properties).requireOwner())
        .isInstanceOf(AccessDeniedException.class);
  }
}
