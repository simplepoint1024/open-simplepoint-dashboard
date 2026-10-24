package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageActivation;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActivationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-serialized activation; instance receipts and resource balances commit together. */
@Service
public class TenantPackageActivationServiceImpl implements TenantPackageActivationService {
  private static final int MAX_RESOURCES = 5000;
  private final EntityManager em;
  private final ResourceAuthorizationVersionService authorization;
  private final Clock clock;

  /** Uses the owning module's persistence binding and a UTC server clock. */
  @Autowired
  public TenantPackageActivationServiceImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization) {
    this(em, authorization, Clock.systemUTC());
  }

  TenantPackageActivationServiceImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization, Clock clock) {
    this.em = em;
    this.authorization = authorization;
    this.clock = clock;
  }

  @Override
  @Transactional
  public TenantPackageActivation activate(String instanceId) {
    var owner = new TenantOwnerBoundary(em, () -> new TenantPackageException(Code.OWNER_REQUIRED));
    owner.currentTenant(false);
    TenantResourceSourceBoundary.enter(
        em, () -> new TenantPackageException(Code.RESOURCE_UNAVAILABLE));
    String tenantId = owner.currentTenant(true);
    if (instanceId == null || instanceId.isBlank() || instanceId.length() > 36) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    var rows =
        em.createQuery(
                "select i from TenantPackageInstance i where i.tenantId = :tenant and i.id = :id",
                TenantPackageInstance.class)
            .setParameter("tenant", tenantId)
            .setParameter("id", instanceId)
            .setMaxResults(1)
            .getResultList();
    if (rows.isEmpty()) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    TenantPackageInstance instance = rows.getFirst();
    if (instance.getAcquisition() != TenantPackageInstance.Acquisition.ASSIGNED) {
      throw new TenantPackageException(Code.INSTANCE_NOT_ACTIVATABLE);
    }
    if (instance.getState() == TenantPackageInstance.State.REVOKED
        || instance.getRevokedAt() != null) {
      throw new TenantPackageException(Code.INSTANCE_REVOKED);
    }
    if (instance.getResourceIds().isEmpty() || instance.getResourceIds().size() > MAX_RESOURCES) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    if (instance.getState() == TenantPackageInstance.State.ACTIVE) {
      return new TenantPackageReceipts(em).activation(instance);
    }
    if (instance.getState() != TenantPackageInstance.State.PENDING
        || instance.getActivatedAt() != null) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    // Both supported databases persist microseconds. Keep original and retried receipts identical.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String actor = AuthorizationContextHolder.getContext().getUserId();
    new TenantResourcePlatformImporter(em)
        .importForActivation(tenantId, instance.getResourceIds(), now);
    for (String resourceId : instance.getResourceIds().stream().sorted().toList()) {
      new TenantResourceCreditIssuer(em).credit(instance, resourceId, now, actor);
    }
    instance.setActivatedAt(now);
    instance.setActivatedBy(actor);
    instance.setState(TenantPackageInstance.State.ACTIVE);
    em.flush();
    authorization.refreshTenant(tenantId);
    return new TenantPackageReceipts(em).activation(instance);
  }
}
