package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceCredit;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageActivation;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageRevocation;

/** Validates original activation evidence before retries and administrative revocation. */
final class TenantPackageReceipts {
  static final int MAX_RESOURCES = 5000;
  private final EntityManager em;

  TenantPackageReceipts(EntityManager em) {
    this.em = em;
  }

  TenantPackageActivation activation(TenantPackageInstance instance) {
    return checkedActivation(instance, null);
  }

  TenantPackageRevocation revocation(TenantPackageInstance instance) {
    if (instance.getRevokedAt() == null || instance.getState() != TenantPackageInstance.State.REVOKED) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    if (instance.getActivatedAt() != null) {
      checkedActivation(instance, instance.getRevokedAt());
    } else if (instance.getActivatedBy() != null
        || em.createQuery("select count(c) from TenantResourceCredit c where c.tenantId = :tenant and c.instanceId = :id",
            Long.class).setParameter("tenant", instance.getTenantId()).setParameter("id", instance.getId())
            .getSingleResult() != 0L) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    return new TenantPackageRevocation(instance.getId(), instance.getRevokedAt());
  }

  private TenantPackageActivation checkedActivation(TenantPackageInstance instance, Instant revokedAt) {
    var credits = em.createQuery("select c from TenantResourceCredit c "
            + "where c.tenantId = :tenant and c.instanceId = :instance order by c.resourceId", TenantResourceCredit.class)
        .setParameter("tenant", instance.getTenantId()).setParameter("instance", instance.getId())
        .setMaxResults(MAX_RESOURCES + 1).getResultList();
    if (instance.getActivatedAt() == null || instance.getActivatedBy() == null
        || instance.getResourceIds().isEmpty() || instance.getResourceIds().size() > MAX_RESOURCES
        || credits.size() != instance.getResourceIds().size()
        || credits.stream().anyMatch(row -> !validCredit(instance, row, revokedAt))) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    List<TenantPackageActivation.ResourceCredit> results = credits.stream()
        .filter(row -> !instance.getResources().get(row.getResourceId()).isSupplemental())
        .map(row -> new TenantPackageActivation.ResourceCredit(
            row.getResourceId(), row.getDurationDays(), row.getResultExpiresAt())).toList();
    if (results.isEmpty()) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    return new TenantPackageActivation(instance.getId(), instance.getActivatedAt(), results);
  }

  private static boolean validCredit(
      TenantPackageInstance instance, TenantResourceCredit credit, Instant revokedAt) {
    TenantPackageResourceDefinition definition = instance.getResources().get(credit.getResourceId());
    if (definition == null || !Objects.equals(credit.getRevokedAt(), revokedAt)
        || !Objects.equals(credit.getDurationDays(), instance.getDurationDays())) {
      return false;
    }
    if (definition.isSupplemental()) {
      return instance.getDurationDays() == null && credit.getActivatedBy() != null
          && !credit.getActivatedAt().isBefore(instance.getActivatedAt());
    }
    return Objects.equals(credit.getActivatedAt(), instance.getActivatedAt())
        && Objects.equals(credit.getActivatedBy(), instance.getActivatedBy());
  }
}
