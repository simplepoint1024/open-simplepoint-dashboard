package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceCredit;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.ResourceEntitlementWindow;

/** Shared identity-transaction ledger writer for purchased credits and explicit historical adoption. */
final class TenantResourceCreditIssuer {
  private final EntityManager em;

  TenantResourceCreditIssuer(EntityManager em) {
    this.em = em;
  }

  void credit(TenantPackageInstance instance, String resourceId, Instant now, String actor) {
    var key = new TenantResourceKey(instance.getTenantId(), resourceId);
    TenantResource resource = em.find(TenantResource.class, key);
    // The importer prepared the definition in this same transaction before issuing any credit.
    if (resource == null || !resource.isPlatformSource()
        || instance.getAcquisition() == TenantPackageInstance.Acquisition.ASSIGNED && resource.isFormallyOffline()) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    TenantResourceEntitlement balance = em.find(TenantResourceEntitlement.class, key);
    final boolean newBalance = balance == null;
    ResourceEntitlementWindow current = balance == null ? null
        : new ResourceEntitlementWindow(balance.getStartsAt(), balance.getExpiresAt());
    ResourceEntitlementWindow next;
    try {
      next = ResourceEntitlementWindow.append(current, now, instance.getDurationDays());
    } catch (IllegalArgumentException exception) {
      throw new TenantPackageException(Code.INVALID_DURATION);
    }
    if (balance == null) {
      balance = new TenantResourceEntitlement();
      balance.setTenantId(instance.getTenantId());
      balance.setId(resourceId);
    }
    balance.setStartsAt(next.startsAt());
    balance.setExpiresAt(next.expiresAt());
    balance.setUpdatedAt(now);
    if (newBalance) {
      em.persist(balance);
    }
    var credit = new TenantResourceCredit();
    credit.setId(UUID.randomUUID().toString());
    credit.setTenantId(instance.getTenantId());
    credit.setInstanceId(instance.getId());
    credit.setResourceId(resourceId);
    credit.setDurationDays(instance.getDurationDays());
    credit.setActivatedAt(now);
    credit.setActivatedBy(actor);
    credit.setResultExpiresAt(next.expiresAt());
    em.persist(credit);
  }

}
