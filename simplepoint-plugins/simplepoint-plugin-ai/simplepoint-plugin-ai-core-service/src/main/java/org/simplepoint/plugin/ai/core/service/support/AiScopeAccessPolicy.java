package org.simplepoint.plugin.ai.core.service.support;

import java.util.Objects;
import java.util.function.Supplier;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Enforces the ownership boundary between platform-managed and tenant-owned AI resources.
 */
@Component
public class AiScopeAccessPolicy {

  private static final String TENANT_ID_HEADER = "X-Tenant-Id";

  private final AiProperties properties;

  private final Supplier<AuthorizationContext> contextSupplier;

  /**
   * Creates the access policy.
   *
   * @param properties AI configuration
   */
  @Autowired
  public AiScopeAccessPolicy(final AiProperties properties) {
    this(properties, AuthorizationContextHolder::getContext);
  }

  AiScopeAccessPolicy(
      final AiProperties properties,
      final Supplier<AuthorizationContext> contextSupplier
  ) {
    this.properties = properties;
    this.contextSupplier = contextSupplier;
  }

  /**
   * Resolves the resource scope owned by the current management context.
   *
   * @return scope and optional tenant id
   */
  public ScopeAssignment currentManagementScope() {
    AuthorizationContext context = requireContext();
    if (AuthorizationScopeGuards.isPlatformAdministrator(context)
        || context.getScopeType() == AuthorizationScopeType.PLATFORM) {
      return new ScopeAssignment(AiResourceScope.SYSTEM, null);
    }
    if (context.getScopeType() != AuthorizationScopeType.TENANT) {
      throw new AccessDeniedException("AI 租户资源仅支持组织租户上下文");
    }
    return new ScopeAssignment(AiResourceScope.TENANT, requireTenantId(context));
  }

  /**
   * Resolves and validates the scope for a create operation.
   *
   * @return scope assignment
   */
  public ScopeAssignment requireCreateScope() {
    ScopeAssignment assignment = currentManagementScope();
    if (assignment.scopeType() == AiResourceScope.TENANT) {
      requireTenantProviderManagementEnabled();
    }
    return assignment;
  }

  /**
   * Ensures that the current context may read the resource in a management API.
   *
   * @param scopeType resource scope
   * @param tenantId resource tenant id
   */
  public void assertCanReadManagedResource(
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    ScopeAssignment current = currentManagementScope();
    if (!matches(current, effectiveScope(scopeType), normalize(tenantId))) {
      throw new AccessDeniedException("AI 资源不存在或不属于当前作用域");
    }
  }

  /**
   * Ensures that the current context may mutate the resource.
   *
   * @param scopeType resource scope
   * @param tenantId resource tenant id
   */
  public void assertCanWriteResource(
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    ScopeAssignment current = currentManagementScope();
    AiResourceScope effectiveScope = effectiveScope(scopeType);
    if (!matches(current, effectiveScope, normalize(tenantId))) {
      throw new AccessDeniedException("AI 资源不存在或不属于当前作用域");
    }
    if (effectiveScope == AiResourceScope.TENANT) {
      requireTenantProviderManagementEnabled();
    }
  }

  /**
   * Ensures that the current context owns a feature resource. Unlike provider
   * configuration, tenant-owned AI features do not require BYOK to be enabled.
   *
   * @param scopeType resource scope
   * @param tenantId resource tenant id
   */
  public void assertCanManageOwnedResource(
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    ScopeAssignment current = currentManagementScope();
    if (!matches(current, effectiveScope(scopeType), normalize(tenantId))) {
      throw new AccessDeniedException("AI 资源不存在或不属于当前作用域");
    }
  }

  /**
   * Returns whether the current context can see configuration buttons.
   *
   * @return true when configuration is allowed
   */
  public boolean canConfigureCurrentScope() {
    try {
      ScopeAssignment assignment = currentManagementScope();
      return assignment.scopeType() == AiResourceScope.SYSTEM
          || Boolean.TRUE.equals(properties.getTenantProviderManagementEnabled());
    } catch (AccessDeniedException ex) {
      return false;
    }
  }

  /**
   * Returns whether a model is available to the current runtime scope.
   *
   * @param scopeType model scope
   * @param tenantId model tenant id
   * @return true when visible for invocation
   */
  public boolean canUseResource(final AiResourceScope scopeType, final String tenantId) {
    AiResourceScope effectiveScope = effectiveScope(scopeType);
    if (effectiveScope == AiResourceScope.SYSTEM) {
      return true;
    }
    try {
      ScopeAssignment current = currentManagementScope();
      return current.scopeType() == AiResourceScope.TENANT
          && Objects.equals(current.tenantId(), normalize(tenantId));
    } catch (AccessDeniedException ex) {
      return false;
    }
  }

  /**
   * Checks resource visibility for durable background work that has no request context.
   *
   * @param resourceScope stored resource scope
   * @param resourceTenantId stored resource tenant id
   * @param invocationScope scope that owns the background work
   * @param invocationTenantId tenant id that owns the background work
   * @return true when the background scope may use the resource
   */
  public boolean canUseResourceFromScope(
      final AiResourceScope resourceScope,
      final String resourceTenantId,
      final AiResourceScope invocationScope,
      final String invocationTenantId
  ) {
    String normalizedInvocationTenantId = normalize(invocationTenantId);
    if (invocationScope == null
        || (invocationScope == AiResourceScope.SYSTEM && normalizedInvocationTenantId != null)
        || (invocationScope == AiResourceScope.TENANT && normalizedInvocationTenantId == null)) {
      return false;
    }
    AiResourceScope effectiveResourceScope = effectiveScope(resourceScope);
    if (effectiveResourceScope == AiResourceScope.SYSTEM) {
      return true;
    }
    return invocationScope == AiResourceScope.TENANT
        && Objects.equals(normalize(resourceTenantId), normalizedInvocationTenantId);
  }

  /**
   * Treats legacy records without a scope as system-owned records.
   *
   * @param scopeType stored scope
   * @return effective scope
   */
  public static AiResourceScope effectiveScope(final AiResourceScope scopeType) {
    return scopeType == null ? AiResourceScope.SYSTEM : scopeType;
  }

  private void requireTenantProviderManagementEnabled() {
    if (!Boolean.TRUE.equals(properties.getTenantProviderManagementEnabled())) {
      throw new AccessDeniedException("当前未开放租户自维护 AI 供应商功能");
    }
  }

  private AuthorizationContext requireContext() {
    AuthorizationContext context = contextSupplier.get();
    if (context == null) {
      throw new AccessDeniedException("缺少 AI 资源访问上下文");
    }
    return context;
  }

  private static String requireTenantId(final AuthorizationContext context) {
    String tenantId = normalize(context.getAttribute(TENANT_ID_HEADER));
    if (tenantId == null) {
      throw new AccessDeniedException("缺少组织租户上下文");
    }
    return tenantId;
  }

  private static boolean matches(
      final ScopeAssignment current,
      final AiResourceScope resourceScope,
      final String resourceTenantId
  ) {
    if (current.scopeType() != resourceScope) {
      return false;
    }
    return resourceScope == AiResourceScope.SYSTEM
        || Objects.equals(current.tenantId(), resourceTenantId);
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  /**
   * Immutable scope assignment.
   *
   * @param scopeType resource scope
   * @param tenantId tenant id for tenant-owned resources
   */
  public record ScopeAssignment(AiResourceScope scopeType, String tenantId) {
  }
}
