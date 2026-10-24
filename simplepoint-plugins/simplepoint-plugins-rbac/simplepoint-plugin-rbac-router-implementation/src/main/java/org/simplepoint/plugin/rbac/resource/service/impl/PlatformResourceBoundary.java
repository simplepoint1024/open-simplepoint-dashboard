package org.simplepoint.plugin.rbac.resource.service.impl;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.AuthorizationScopeType;
import org.springframework.security.access.AccessDeniedException;

/**
 * Browser catalog access is platform-scoped; trusted declaration installation has its own boundary.
 */
final class PlatformResourceBoundary {
  private PlatformResourceBoundary() {}

  static void read(AuthorizationContext context) {
    if (context == null
        || context.getUserId() == null
        || context.getUserId().isBlank()
        || context.getScopeType() != AuthorizationScopeType.PLATFORM
        || context.getAttribute("X-Tenant-Id") != null
            && !context.<String>getAttribute("X-Tenant-Id").isBlank()) {
      throw new AccessDeniedException("平台资源目录要求明确的平台工作空间");
    }
  }

  static void write(AuthorizationContext context, String authority) {
    read(context);
    if (!AuthorizationScopeGuards.isPlatformAdministrator(context)
        && (context.getResources() == null || !context.getResources().contains(authority))) {
      throw new AccessDeniedException("缺少平台资源操作权限");
    }
  }

  static void distribute(AuthorizationContext context) {
    read(context);
    AuthorizationScopeGuards.requirePlatformAdministrator(context);
  }
}
