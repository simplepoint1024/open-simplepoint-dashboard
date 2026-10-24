package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.ResourceEntitlementLedger;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageRevocation;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageRevocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revocation, remaining balances and authorization invalidation commit under one tenant lock. */
@Service
public class TenantPackageRevocationServiceImpl implements TenantPackageRevocationService {
  private final EntityManager em;
  private final ResourceAuthorizationVersionService authorization;
  private final Clock clock;

  /** Uses identity's persistence binding and the UTC server clock. */
  @Autowired
  public TenantPackageRevocationServiceImpl(EntityManager em, ResourceAuthorizationVersionService authorization) {
    this(em, authorization, Clock.systemUTC());
  }

  TenantPackageRevocationServiceImpl(EntityManager em, ResourceAuthorizationVersionService authorization, Clock clock) {
    this.em = em;
    this.authorization = authorization;
    this.clock = clock;
  }

  @Override
  @Transactional
  public TenantPackageRevocation revoke(String tenantId, String instanceId) {
    final String actor = TenantPackagePlatformBoundary.administrator();
    TenantPackagePlatformBoundary.lockTenant(em, tenantId);
    if (instanceId == null || instanceId.isBlank() || instanceId.length() > 36) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    var instances = em.createQuery("select i from TenantPackageInstance i where i.tenantId = :tenant and i.id = :id",
            TenantPackageInstance.class).setParameter("tenant", tenantId).setParameter("id", instanceId)
        .setMaxResults(1).getResultList();
    if (instances.isEmpty()) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    TenantPackageInstance instance = instances.getFirst();
    if (instance.getState() == TenantPackageInstance.State.REVOKED && instance.getRevokedAt() != null) {
      return new TenantPackageReceipts(em).revocation(instance);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    boolean active = instance.getState() == TenantPackageInstance.State.ACTIVE;
    if (instance.getRevokedAt() != null || instance.getState() != TenantPackageInstance.State.PENDING && !active
        || instance.getAssignedAt().isAfter(now) || instance.getActivatedAt() != null && instance.getActivatedAt().isAfter(now)) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    if (active) {
      new TenantPackageReceipts(em).activation(instance);
    } else if (instance.getActivatedAt() != null || instance.getActivatedBy() != null
        || em.createQuery("select count(c) from TenantResourceCredit c where c.tenantId = :tenant and c.instanceId = :id",
            Long.class).setParameter("tenant", tenantId).setParameter("id", instanceId).getSingleResult() != 0L) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    instance.setState(TenantPackageInstance.State.REVOKED);
    instance.setRevokedAt(now);
    instance.setRevokedBy(actor);
    em.flush();
    if (active) {
      // Bulk update only mutable revocation facts. Original activation times and result expiries remain unchanged.
      int changed = em.createQuery("update TenantResourceCredit c set c.revokedAt = :now "
              + "where c.tenantId = :tenant and c.instanceId = :id and c.revokedAt is null")
          .setParameter("now", now).setParameter("tenant", tenantId).setParameter("id", instanceId).executeUpdate();
      if (changed != instance.getResourceIds().size()) {
        throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
      }
      for (String resourceId : instance.getResourceIds().stream().sorted().toList()) {
        recalculate(tenantId, resourceId, now);
      }
      em.flush();
      authorization.refreshTenant(tenantId);
    }
    return new TenantPackageRevocation(instanceId, now);
  }

  private void recalculate(String tenantId, String resourceId, Instant now) {
    var key = new TenantResourceKey(tenantId, resourceId);
    var balance = em.find(TenantResourceEntitlement.class, key);
    // Scalar projections see bulk revocations even when original receipts were loaded in this transaction.
    try (var credits = em.createQuery("select new org.simplepoint.plugin.rbac.tenant.api.model.ResourceEntitlementLedger$Credit"
            + "(c.id, c.activatedAt, c.durationDays, c.revokedAt) from TenantResourceCredit c "
            + "where c.tenantId = :tenant and c.resourceId = :resource order by c.activatedAt, c.id",
            ResourceEntitlementLedger.Credit.class)
        .setParameter("tenant", tenantId).setParameter("resource", resourceId)
        .setHint("org.hibernate.fetchSize", 128).getResultStream()) {
      var remaining = ResourceEntitlementLedger.remaining(credits.iterator(), now);
      if (remaining.isEmpty()) {
        if (balance != null) {
          em.remove(balance);
        }
        return;
      }
      if (balance == null) {
        throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
      }
      balance.setExpiresAt(remaining.get().expiresAt());
      balance.setUpdatedAt(now);
    } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException exception) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
  }
}
