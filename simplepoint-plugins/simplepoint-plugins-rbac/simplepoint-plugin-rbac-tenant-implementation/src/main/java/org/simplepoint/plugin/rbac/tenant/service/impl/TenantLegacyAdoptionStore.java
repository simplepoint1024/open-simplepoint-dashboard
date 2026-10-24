package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity-internal transaction store shared by authorized HTTP operations and explicit offline
 * installation.
 */
@Service
@Transactional(readOnly = true)
public class TenantLegacyAdoptionStore {
  private final EntityManager em;
  private final ResourceAuthorizationVersionService authorization;
  private final Clock clock;

  /** Uses identity persistence and its authorization invalidation boundary. */
  @Autowired
  public TenantLegacyAdoptionStore(
      EntityManager em, ResourceAuthorizationVersionService authorization) {
    this(em, authorization, Clock.systemUTC());
  }

  TenantLegacyAdoptionStore(
      EntityManager em, ResourceAuthorizationVersionService authorization, Clock clock) {
    this.em = em;
    this.authorization = authorization;
    this.clock = clock;
  }

  /** Read-only advisory preview; execution does not compare resource snapshots or versions. */
  public TenantLegacyAdoption.Preview preview(String tenantId) {
    requirePending(tenantId);
    var plan = prepare(tenantId, now());
    return new TenantLegacyAdoption.Preview(
        tenantId,
        plan.directory().additions().size(),
        plan.legacyIds().size(),
        plan.baseline().size(),
        plan.groups().stream().map(TenantLegacyEntitlementReader.Group::summary).toList());
  }

  /**
   * Commits one tenant, including its authorization invalidation, or returns its original receipt.
   */
  @Transactional
  public TenantLegacyAdoption adopt(String tenantId, String actor) {
    if (actor == null || actor.isBlank() || actor.length() > 36) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    TenantResourceSourceBoundary.enter(
        em, () -> new TenantPackageException(Code.RESOURCE_UNAVAILABLE));
    TenantPackagePlatformBoundary.lockTenant(em, tenantId);
    TenantResourceSetup setup = em.find(TenantResourceSetup.class, tenantId);
    if (setup != null
        && setup.getState() == TenantResourceSetup.State.READY
        && setup.getAdoptedAt() != null) {
      return receipt(setup);
    }
    requirePending(tenantId);
    Instant now = now();
    var plan = prepare(tenantId, now);
    new TenantResourcePlatformImporter(em).apply(plan.directory());
    var credits = new TenantResourceCreditIssuer(em);
    for (var group : plan.groups()) {
      var instance = new TenantPackageInstance();
      instance.setId(UUID.randomUUID().toString());
      instance.setTenantId(tenantId);
      instance.setPackageCode(group.code());
      instance.setPackageName(group.name());
      instance.setAcquisition(group.acquisition());
      instance.setAdoptedAt(now);
      instance.setAssignedAt(now);
      instance.setAssignedBy(actor);
      instance.setState(TenantPackageInstance.State.ACTIVE);
      // This is the ledger's adoption anchor, never a fabricated historical activation date.
      instance.setActivatedAt(now);
      instance.setActivatedBy(actor);
      instance.setResources(group.resources());
      em.persist(instance);
      for (String id : instance.getResourceIds().stream().sorted().toList()) {
        credits.credit(instance, id, now, actor);
      }
    }
    setup.setState(TenantResourceSetup.State.READY);
    setup.setInitializedAt(now);
    setup.setAdoptedAt(now);
    setup.setAdoptedBy(actor);
    em.flush();
    authorization.refreshTenant(tenantId);
    return receipt(setup);
  }

  /** Enumerates active tenants requiring explicit adoption using a bounded stable cursor. */
  public List<String> pending(String after, int limit) {
    if (limit < 1 || limit > 100 || after != null && (after.isBlank() || after.length() > 36)) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    long unmarked =
        em.createQuery(
                "select count(t) from Tenant t where t.deletedAt is null and not exists (select 1"
                    + " from TenantResourceSetup s where s.tenantId = t.id)",
                Long.class)
            .getSingleResult();
    if (unmarked != 0) {
      throw new TenantPackageException(Code.RESOURCE_CONFLICT);
    }
    var query =
        em.createQuery(
                "select s.tenantId from TenantResourceSetup s, Tenant t "
                    + "where s.tenantId = t.id and t.deletedAt is null and s.state = :pending "
                    + (after == null ? "" : "and s.tenantId > :after ")
                    + "order by s.tenantId",
                String.class)
            .setParameter("pending", TenantResourceSetup.State.ADOPTION_REQUIRED)
            .setMaxResults(limit);
    if (after != null) {
      query.setParameter("after", after);
    }
    return List.copyOf(query.getResultList());
  }

  /** Reads durable completion even after subsequent credit revocation; never replays adoption. */
  public java.util.Optional<TenantLegacyAdoption> completed(String tenantId) {
    if (tenantId == null || tenantId.isBlank() || tenantId.length() > 36) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    TenantResourceSetup setup = em.find(TenantResourceSetup.class, tenantId);
    if (setup == null || setup.getAdoptedAt() == null) {
      return java.util.Optional.empty();
    }
    if (setup.getState() != TenantResourceSetup.State.READY) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    return java.util.Optional.of(receipt(setup));
  }

  private record Plan(
      TenantResourcePlatformImporter.Plan directory,
      List<TenantLegacyEntitlementReader.Group> groups,
      Set<String> legacyIds,
      Set<String> baseline) {}

  private Plan prepare(String tenantId, Instant now) {
    var tenants =
        em.createQuery(
                "select t.tenantType, t.resourceMode from Tenant t "
                    + "where t.id = :tenant and t.deletedAt is null",
                Object[].class)
            .setParameter("tenant", tenantId)
            .setMaxResults(1)
            .getResultList();
    if (tenants.isEmpty()) {
      throw new TenantPackageException(Code.TENANT_NOT_FOUND);
    }
    TenantType type = (TenantType) tenants.getFirst()[0];
    TenantResourceMode mode = (TenantResourceMode) tenants.getFirst()[1];
    var groups = new TenantLegacyEntitlementReader(em).read(tenantId, type, mode);
    var legacy = new LinkedHashSet<String>();
    groups.forEach(group -> legacy.addAll(group.resources().keySet()));
    var baseline = new TenantResourceDistributionPolicy(em).baseline(type);
    var ids = new LinkedHashSet<>(legacy);
    ids.addAll(baseline);
    ids.addAll(
        em.createQuery(
                "select r.id from TenantResource r where r.tenantId = :tenant "
                    + "and r.platformResourceId is not null order by r.id",
                String.class)
            .setParameter("tenant", tenantId)
            .setMaxResults(10001)
            .getResultList());
    if (ids.size() > 10000) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    var directory =
        new TenantResourcePlatformImporter(em).prepare(tenantId, ids, now, false, baseline, false);
    for (TenantResource row : directory.rows().values()) {
      TenantResource previous =
          em.find(TenantResource.class, new TenantResourceKey(tenantId, row.getId()));
      // Preserve old local disablement and a CUSTOM catalog's deliberate omissions. A later
      // explicit update may import them.
      if (previous != null && !previous.isEnabled()
          || previous == null
              && mode == TenantResourceMode.CUSTOM
              && row.isGrantable()
              && !row.isPublicAccess()
              && !baseline.contains(row.getId())) {
        row.setEnabled(false);
        if (previous != null && TenantResourcePlatformImporter.sameDefinition(previous, row)) {
          row.setUpdatedAt(previous.getUpdatedAt());
        }
      }
    }
    return new Plan(directory, groups, Set.copyOf(legacy), baseline);
  }

  private void requirePending(String tenantId) {
    if (tenantId == null || tenantId.isBlank() || tenantId.length() > 36) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    TenantResourceSetup setup = em.find(TenantResourceSetup.class, tenantId);
    if (setup == null || setup.getState() != TenantResourceSetup.State.ADOPTION_REQUIRED) {
      throw new TenantPackageException(Code.ADOPTION_NOT_REQUIRED);
    }
    if (setup.getInitializedAt() != null
        || setup.getAdoptedAt() != null
        || setup.getAdoptedBy() != null) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    long existing =
        em.createQuery(
                "select count(i) from TenantPackageInstance i where i.tenantId = :tenant "
                    + "and i.acquisition <> :assigned",
                Long.class)
            .setParameter("tenant", tenantId)
            .setParameter("assigned", TenantPackageInstance.Acquisition.ASSIGNED)
            .getSingleResult();
    if (existing != 0) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
  }

  private TenantLegacyAdoption receipt(TenantResourceSetup setup) {
    if (setup.getInitializedAt() == null || setup.getAdoptedBy() == null) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    var counts =
        em.createQuery(
                "select count(distinct c.resourceId), count(distinct c.instanceId) from"
                    + " TenantResourceCredit c, TenantPackageInstance i where c.instanceId = i.id"
                    + " and c.tenantId = :tenant and i.tenantId = :tenant and i.acquisition <>"
                    + " :assigned",
                Object[].class)
            .setParameter("tenant", setup.getTenantId())
            .setParameter("assigned", TenantPackageInstance.Acquisition.ASSIGNED)
            .getSingleResult();
    return new TenantLegacyAdoption(
        setup.getTenantId(),
        setup.getAdoptedAt(),
        setup.getAdoptedBy(),
        Math.toIntExact((Long) counts[0]),
        Math.toIntExact((Long) counts[1]));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
