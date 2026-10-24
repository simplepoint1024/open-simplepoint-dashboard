package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException.Code;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One transactional definition pipeline for new tenants and owner-initiated platform updates. */
@Service
@Transactional(readOnly = true)
public class TenantResourceProvisioningServiceImpl implements TenantResourceProvisioningService {
  private final EntityManager em;
  private final ResourceAuthorizationVersionService authorization;
  private final Clock clock;

  /** Uses the identity module's persistence binding. */
  @Autowired
  public TenantResourceProvisioningServiceImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization) {
    this(em, authorization, Clock.systemUTC());
  }

  TenantResourceProvisioningServiceImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization, Clock clock) {
    this.em = em;
    this.authorization = authorization;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(String tenantId) {
    TenantResourceSourceBoundary.enter(
        em, () -> new TenantResourceSetupException(Code.RESOURCE_UNAVAILABLE));
    TenantType type = lockTenant(tenantId);
    TenantResourceSetup setup = em.find(TenantResourceSetup.class, tenantId);
    if (setup != null && setup.getState() != TenantResourceSetup.State.INITIALIZATION_REQUIRED) {
      requireReady(setup);
      return;
    }
    if (setup != null
        && (setup.getInitializedAt() != null
            || setup.getAdoptedAt() != null
            || setup.getAdoptedBy() != null)) {
      throw new TenantResourceSetupException(Code.RESOURCE_CONFLICT);
    }
    Instant now = now();
    var baseline = new TenantResourceDistributionPolicy(em).baseline(type);
    var importer = new TenantResourcePlatformImporter(em);
    try {
      importer.apply(importer.prepare(tenantId, baseline, now, false, baseline, true));
    } catch (TenantPackageException error) {
      throw directoryFailure(error);
    }
    final boolean newSetup = setup == null;
    if (newSetup) {
      setup = new TenantResourceSetup();
      setup.setTenantId(tenantId);
    }
    setup.setState(TenantResourceSetup.State.READY);
    setup.setInitializedAt(now);
    if (newSetup) {
      em.persist(setup);
    }
    em.flush();
    authorization.refreshTenant(tenantId);
  }

  @Override
  public TenantResourceCatalog.SyncPreview preview(boolean overwrite) {
    String tenantId = currentOwner(false);
    var plan = prepareUpdate(tenantId, overwrite);
    var additions =
        plan.additions().stream().sorted().limit(100).map(id -> item(plan.rows().get(id))).toList();
    var disabled =
        plan.rows().values().stream()
            .filter(row -> !row.isSourceAvailable())
            .sorted(java.util.Comparator.comparing(TenantResource::getId))
            .limit(100)
            .map(TenantResourceProvisioningServiceImpl::item)
            .toList();
    return new TenantResourceCatalog.SyncPreview(
        plan.additions().size(), updated(plan), plan.unavailable(), additions, disabled, overwrite);
  }

  @Override
  @Transactional
  public TenantResourceCatalog.SyncResult synchronize(boolean overwrite) {
    currentOwner(false);
    TenantResourceSourceBoundary.enter(
        em, () -> new TenantResourceSetupException(Code.RESOURCE_UNAVAILABLE));
    String tenantId = currentOwner(true);
    var plan = prepareUpdate(tenantId, overwrite);
    new TenantResourcePlatformImporter(em).apply(plan);
    em.flush();
    if (!plan.changes().isEmpty()) {
      authorization.refreshTenant(tenantId);
    }
    return new TenantResourceCatalog.SyncResult(
        plan.additions().size(), updated(plan), plan.unavailable());
  }

  private TenantResourcePlatformImporter.Plan prepareUpdate(String tenantId, boolean overwrite) {
    requireReady(em.find(TenantResourceSetup.class, tenantId));
    TenantType type =
        em.createQuery(
                "select t.tenantType from Tenant t where t.id = :tenant and t.deletedAt is null",
                TenantType.class)
            .setParameter("tenant", tenantId)
            .getSingleResult();
    var baseline = new TenantResourceDistributionPolicy(em).baseline(type);
    var ids = new LinkedHashSet<>(baseline);
    ids.addAll(
        em.createQuery(
                "select r.id from TenantResource r where r.tenantId = :tenant "
                    + "and r.platformResourceId is not null order by r.id",
                String.class)
            .setParameter("tenant", tenantId)
            .setMaxResults(10001)
            .getResultList());
    if (ids.size() > 10000) {
      throw new TenantResourceSetupException(Code.RESOURCE_UNAVAILABLE);
    }
    try {
      return new TenantResourcePlatformImporter(em)
          .prepare(tenantId, ids, now(), overwrite, baseline, false);
    } catch (TenantPackageException error) {
      throw directoryFailure(error);
    }
  }

  private TenantType lockTenant(String tenantId) {
    if (tenantId == null || tenantId.isBlank() || tenantId.length() > 36) {
      throw new TenantResourceSetupException(Code.TENANT_UNAVAILABLE);
    }
    List<TenantType> rows =
        em.createQuery(
                "select t.tenantType from Tenant t where t.id = :tenant and t.deletedAt is null",
                TenantType.class)
            .setParameter("tenant", tenantId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setMaxResults(1)
            .getResultList();
    if (rows.isEmpty() || rows.getFirst() == null) {
      throw new TenantResourceSetupException(Code.TENANT_UNAVAILABLE);
    }
    return rows.getFirst();
  }

  private String currentOwner(boolean lock) {
    return new TenantOwnerBoundary(em, () -> new TenantResourceSetupException(Code.OWNER_REQUIRED))
        .currentTenant(lock);
  }

  private static void requireReady(TenantResourceSetup setup) {
    if (setup == null || setup.getState() == TenantResourceSetup.State.INITIALIZATION_REQUIRED) {
      throw new TenantResourceSetupException(Code.CATALOG_NOT_READY);
    }
    if (setup.getState() != TenantResourceSetup.State.READY || setup.getInitializedAt() == null) {
      throw new TenantResourceSetupException(Code.ADOPTION_REQUIRED);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static int updated(TenantResourcePlatformImporter.Plan plan) {
    return plan.changes().size() - plan.additions().size();
  }

  private static TenantResourceCatalog.SyncItem item(TenantResource row) {
    return new TenantResourceCatalog.SyncItem(row.getId(), row.getCode(), row.getName());
  }

  private static TenantResourceSetupException directoryFailure(TenantPackageException error) {
    return new TenantResourceSetupException(
        error.code() == TenantPackageException.Code.RESOURCE_CONFLICT
            ? Code.RESOURCE_CONFLICT
            : Code.RESOURCE_UNAVAILABLE);
  }
}
