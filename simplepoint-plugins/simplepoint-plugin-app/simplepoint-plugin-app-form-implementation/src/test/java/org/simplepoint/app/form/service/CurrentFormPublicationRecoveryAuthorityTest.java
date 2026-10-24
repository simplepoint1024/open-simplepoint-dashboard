package org.simplepoint.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.security.ResourceScopeCatalog;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.entity.ResourceScopeType;
import org.springframework.security.authentication.BadCredentialsException;

class CurrentFormPublicationRecoveryAuthorityTest {
  private final AuthorizationContextService contexts = mock(AuthorizationContextService.class);
  private final ResourceScopeCatalog scopes = mock(ResourceScopeCatalog.class);
  private final CurrentFormPublicationRecoveryAuthority authority =
      new CurrentFormPublicationRecoveryAuthority(contexts, scopes);

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void resolvesFreshAuthorityOnEveryAttemptAndRestoresTheOwningThreadContext() {
    var previous = new AuthorizationContext();
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
    ResourcePolicyContext.set("previous-page");
    var current = valid();
    when(contexts.calculate(eq("tenant-a"), eq("actor-a"), isNull(), any())).thenReturn(current);
    for (int i = 0; i < 2; i++) {
      try (var ignored = authority.open("tenant-a", "actor-a", "role-a")) {
        assertThat(AuthorizationContextHolder.getContext()).isSameAs(current);
        assertThat(ResourcePolicyContext.get()).isEqualTo("app.workbench.view");
      }
      assertThat(AuthorizationContextHolder.getContext()).isSameAs(previous);
      assertThat(ResourcePolicyContext.get()).isEqualTo("previous-page");
    }
    verify(contexts, times(2))
        .calculate(
            "tenant-a",
            "actor-a",
            null,
            Map.of("X-Tenant-Id", "tenant-a", "X-User-Id", "actor-a", "X-Role-Id", "role-a"));
    when(contexts.calculate(eq("tenant-a"), eq("actor-a"), isNull(), any()))
        .thenThrow(new BadCredentialsException("disabled"));
    denied();
    assertThat(AuthorizationContextHolder.getContext()).isSameAs(previous);
  }

  @Test
  void rejectsChangedIdentityRevokedPermissionsAndOutOfScopeAdministrators() {
    for (String scenario :
        java.util.List.of(
            "actor", "tenant", "role", "grant", "tenantAdmin", "scope", "missingCatalog")) {
      var current = valid(scenario);
      if (scenario.equals("missingCatalog")) {
        when(scopes.findScopes("app.workbench.forms.publish")).thenReturn(Optional.empty());
      }
      when(contexts.calculate(eq("tenant-a"), eq("actor-a"), isNull(), any())).thenReturn(current);
      denied();
      assertThat(AuthorizationContextHolder.getContext()).isNull();
      assertThat(ResourcePolicyContext.get()).isNull();
    }
  }

  @Test
  void transientAuthorityServiceFailuresRemainRetryableWithoutInstallingContext() {
    when(contexts.calculate(eq("tenant-a"), eq("actor-a"), isNull(), any()))
        .thenThrow(new IllegalStateException("RPC unavailable"));
    assertThatThrownBy(() -> authority.open("tenant-a", "actor-a", "role-a"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
  }

  private AuthorizationContext valid() {
    return valid("valid");
  }

  private AuthorizationContext valid(String scenario) {
    when(scopes.findScopes(any())).thenReturn(Optional.of(Set.of(ResourceScopeType.TENANT)));
    var current = new AuthorizationContext();
    current.setUserId(scenario.equals("actor") ? "another-actor" : "actor-a");
    current.setAttributes(
        Map.of(
            "X-Tenant-Id",
            scenario.equals("tenant") ? "other" : "tenant-a",
            "X-Role-Id",
            scenario.equals("role") ? "other" : "role-a"));
    current.setScopeType(
        scenario.equals("scope") ? AuthorizationScopeType.PERSONAL : AuthorizationScopeType.TENANT);
    current.setIsAdministrator(scenario.equals("scope") || scenario.equals("tenantAdmin"));
    current.setResources(
        (scenario.equals("grant") || scenario.equals("tenantAdmin"))
            ? Set.of("app.workbench.view")
            : Set.of("app.workbench.view", "app.workbench.forms.publish"));
    return current;
  }

  private void denied() {
    assertThatThrownBy(() -> authority.open("tenant-a", "actor-a", "role-a"))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
  }
}
