package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;

/** Explicit platform administration and the same tenant lock used by activation. */
final class TenantPackagePlatformBoundary {
  private TenantPackagePlatformBoundary() {}

  static String administrator() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null
        || context.getUserId() == null
        || context.getScopeType() != AuthorizationScopeType.PLATFORM
        || !AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      throw new TenantPackageException(Code.PLATFORM_ADMIN_REQUIRED);
    }
    return context.getUserId();
  }

  static TenantType lockTenant(EntityManager em, String tenantId) {
    return tenant(em, tenantId, true);
  }

  static void requireTenant(EntityManager em, String tenantId) {
    tenant(em, tenantId, false);
  }

  private static TenantType tenant(EntityManager em, String tenantId, boolean lock) {
    if (tenantId == null || tenantId.isBlank() || tenantId.length() > 36) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    var query =
        em.createQuery(
                "select t.tenantType from Tenant t where t.id = :tenant and t.deletedAt is null",
                TenantType.class)
            .setParameter("tenant", tenantId)
            .setMaxResults(1);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    var tenants = query.getResultList();
    if (tenants.isEmpty() || tenants.getFirst() == null) {
      throw new TenantPackageException(Code.TENANT_NOT_FOUND);
    }
    return tenants.getFirst();
  }
}
