package org.simplepoint.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.security.entity.ResourceScopeType;

class ResourceScopePolicyTest {

  @Test
  void defaultsLegacyResourcesToSystemScope() {
    assertThat(ResourceScopePolicy.effectiveScopes(Set.of()))
        .containsExactly(ResourceScopeType.SYSTEM);
  }

  @Test
  void systemResourcesRequirePlatformAdministratorContext() {
    AuthorizationContext administrator = context(AuthorizationScopeType.PLATFORM, true);
    AuthorizationContext platformUser = context(AuthorizationScopeType.PLATFORM, false);
    AuthorizationContext tenantAdministrator = context(AuthorizationScopeType.TENANT, true);

    assertThat(ResourceScopePolicy.isAccessible(Set.of(ResourceScopeType.SYSTEM), administrator)).isTrue();
    assertThat(ResourceScopePolicy.isAccessible(Set.of(ResourceScopeType.SYSTEM), platformUser)).isFalse();
    assertThat(ResourceScopePolicy.isAccessible(Set.of(ResourceScopeType.SYSTEM), tenantAdministrator)).isFalse();
  }

  @Test
  void matchesPlatformTenantAndPersonalScopesExactly() {
    Set<ResourceScopeType> scopes = Set.of(
        ResourceScopeType.PLATFORM,
        ResourceScopeType.TENANT
    );

    assertThat(ResourceScopePolicy.isAccessible(
        scopes,
        context(AuthorizationScopeType.PLATFORM, false)
    )).isTrue();
    assertThat(ResourceScopePolicy.isAccessible(
        scopes,
        context(AuthorizationScopeType.TENANT, false)
    )).isTrue();
    assertThat(ResourceScopePolicy.isAccessible(
        scopes,
        context(AuthorizationScopeType.PERSONAL, false)
    )).isFalse();
  }

  @Test
  void rejectsChildScopesOutsideParentBoundary() {
    assertThat(ResourceScopePolicy.isValidChild(
        Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL),
        Set.of(ResourceScopeType.TENANT)
    )).isTrue();
    assertThat(ResourceScopePolicy.isValidChild(
        Set.of(ResourceScopeType.TENANT),
        Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL)
    )).isFalse();
  }

  private AuthorizationContext context(AuthorizationScopeType scopeType, boolean administrator) {
    AuthorizationContext context = new AuthorizationContext();
    context.setScopeType(scopeType);
    context.setIsAdministrator(administrator);
    return context;
  }
}
