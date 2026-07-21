package org.simplepoint.plugin.ai.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.security.access.AccessDeniedException;

class AiScopeAccessPolicyTest {

  @Test
  void platformAdministratorOwnsSystemScope() {
    AiScopeAccessPolicy policy = policy(platformContext(true), false);

    ScopeAssignment assignment = policy.requireCreateScope();

    assertEquals(AiResourceScope.SYSTEM, assignment.scopeType());
    assertNull(assignment.tenantId());
    assertTrue(policy.canConfigureCurrentScope());
  }

  @Test
  void platformScopedOperatorOwnsSystemScope() {
    AiScopeAccessPolicy policy = policy(platformContext(false), true);

    ScopeAssignment assignment = policy.requireCreateScope();

    assertEquals(AiResourceScope.SYSTEM, assignment.scopeType());
    assertNull(assignment.tenantId());
  }

  @Test
  void tenantProviderManagementIsEnabledByDefault() {
    assertTrue(new AiProperties().getTenantProviderManagementEnabled());
  }

  @Test
  void tenantCreateRequiresByokFeature() {
    AiScopeAccessPolicy policy = policy(tenantContext("tenant-a"), false);

    assertThrows(AccessDeniedException.class, policy::requireCreateScope);
    assertFalse(policy.canConfigureCurrentScope());
  }

  @Test
  void enabledTenantOwnsOnlyItsResources() {
    AiScopeAccessPolicy policy = policy(tenantContext("tenant-a"), true);

    ScopeAssignment assignment = policy.requireCreateScope();

    assertEquals(AiResourceScope.TENANT, assignment.scopeType());
    assertEquals("tenant-a", assignment.tenantId());
    policy.assertCanWriteResource(AiResourceScope.TENANT, "tenant-a");
    assertThrows(
        AccessDeniedException.class,
        () -> policy.assertCanWriteResource(AiResourceScope.TENANT, "tenant-b")
    );
    assertThrows(
        AccessDeniedException.class,
        () -> policy.assertCanWriteResource(AiResourceScope.SYSTEM, null)
    );
    assertThrows(
        AccessDeniedException.class,
        () -> policy.assertCanReadManagedResource(AiResourceScope.TENANT, "tenant-b")
    );
  }

  @Test
  void tenantCanUseSharedAndOwnModels() {
    AiScopeAccessPolicy policy = policy(tenantContext("tenant-a"), false);

    assertTrue(policy.canUseResource(AiResourceScope.SYSTEM, null));
    assertTrue(policy.canUseResource(AiResourceScope.TENANT, "tenant-a"));
    assertFalse(policy.canUseResource(AiResourceScope.TENANT, "tenant-b"));
  }

  private static AiScopeAccessPolicy policy(
      final AuthorizationContext context,
      final boolean tenantManagementEnabled
  ) {
    AiProperties properties = new AiProperties();
    properties.setTenantProviderManagementEnabled(tenantManagementEnabled);
    return new AiScopeAccessPolicy(properties, () -> context);
  }

  private static AuthorizationContext platformContext(final boolean administrator) {
    AuthorizationContext context = new AuthorizationContext();
    context.setIsAdministrator(administrator);
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setAttributes(Map.of());
    return context;
  }

  private static AuthorizationContext tenantContext(final String tenantId) {
    AuthorizationContext context = new AuthorizationContext();
    context.setIsAdministrator(false);
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    return context;
  }
}
