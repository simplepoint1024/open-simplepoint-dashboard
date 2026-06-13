package org.simplepoint.core;

import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * Guard helpers for platform, tenant, and personal authorization scopes.
 */
public final class AuthorizationScopeGuards {

  private AuthorizationScopeGuards() {
  }

  /**
   * Is Platform Administrator.
   */
  public static boolean isPlatformAdministrator(AuthorizationContext context) {
    return context != null
        && Boolean.TRUE.equals(context.getIsAdministrator())
        && (context.getScopeType() == null || context.getScopeType() == AuthorizationScopeType.PLATFORM);
  }

  /**
   * Is Tenant Manager.
   */
  public static boolean isTenantManager(AuthorizationContext context, String tenantOwnerId) {
    if (context == null) {
      return false;
    }
    if (Boolean.TRUE.equals(context.getIsAdministrator())) {
      return true;
    }
    if (context.getScopeType() == AuthorizationScopeType.PERSONAL
        || context.getActorRole() == AuthorizationActorRole.PERSONAL_OWNER
        || context.getActorRole() == AuthorizationActorRole.PERSONAL_MEMBER) {
      return false;
    }
    if (context.getActorRole() == AuthorizationActorRole.TENANT_ADMIN
        || context.getActorRole() == AuthorizationActorRole.TENANT_OWNER) {
      return true;
    }
    return tenantOwnerId != null && Objects.equals(tenantOwnerId, context.getUserId());
  }

  /**
   * Require Platform Administrator.
   */
  public static void requirePlatformAdministrator(AuthorizationContext context) {
    if (!isPlatformAdministrator(context)) {
      throw new AccessDeniedException("仅平台管理员可以执行该操作");
    }
  }

  /**
   * Require Organization Tenant Manager.
   */
  public static void requireOrganizationTenantManager(AuthorizationContext context, String tenantOwnerId) {
    if (!isTenantManager(context, tenantOwnerId)) {
      throw new AccessDeniedException("仅组织租户所有者或租户管理员可以执行该操作");
    }
  }
}
