package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;

/** Fresh database ownership checks shared by tenant-owned reads and writes. */
final class TenantOwnerBoundary {
  private final EntityManager em;
  private final java.util.function.Supplier<? extends RuntimeException> denied;

  TenantOwnerBoundary(EntityManager em, java.util.function.Supplier<? extends RuntimeException> denied) {
    this.em = em;
    this.denied = denied;
  }

  String currentTenant(boolean lock) {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null
        || (context.getScopeType() != AuthorizationScopeType.TENANT
            && context.getScopeType() != AuthorizationScopeType.PERSONAL)) {
      throw denied.get();
    }
    String tenantId = context.getAttribute("X-Tenant-Id");
    if (tenantId == null || tenantId.isBlank()) {
      throw denied.get();
    }
    // Scalar values revalidate ownership without trusting a cached entity or an administrator flag.
    var query = em.createQuery("select t.ownerId as owner, t.tenantType as type from Tenant t "
            + "where t.id = :tenant and t.deletedAt is null", Tuple.class)
        .setParameter("tenant", tenantId).setMaxResults(1);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    var tenants = query.getResultList();
    if (tenants.isEmpty() || !context.getUserId().equals(tenants.getFirst().get("owner", String.class))
        || (context.getScopeType() == AuthorizationScopeType.PERSONAL
            ? tenants.getFirst().get("type", TenantType.class) != TenantType.PERSONAL
            : tenants.getFirst().get("type", TenantType.class) != TenantType.ORGANIZATION)) {
      throw denied.get();
    }
    return tenantId;
  }
}
