package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.AuthorizationScopeType;
import org.springframework.security.access.AccessDeniedException;

/**
 * Platform package/application templates never resolve tenant licenses or personal-space defaults.
 */
final class PlatformTemplateBoundary {
  private PlatformTemplateBoundary() {}

  static void read(AuthorizationContext context) {
    if (context == null
        || context.getUserId() == null
        || context.getUserId().isBlank()
        || context.getScopeType() != AuthorizationScopeType.PLATFORM
        || context.getAttribute("X-Tenant-Id") != null
            && !context.<String>getAttribute("X-Tenant-Id").isBlank()) {
      throw new AccessDeniedException("平台模板目录要求明确的平台工作空间");
    }
  }

  static void write(AuthorizationContext context) {
    read(context);
    AuthorizationScopeGuards.requirePlatformAdministrator(context);
  }
}
