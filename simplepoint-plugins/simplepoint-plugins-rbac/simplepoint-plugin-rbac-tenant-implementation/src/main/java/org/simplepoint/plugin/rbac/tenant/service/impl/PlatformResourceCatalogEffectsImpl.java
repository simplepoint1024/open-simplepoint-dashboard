package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform and tenant catalogs belong to the same identity module and store. Critical definition
 * changes, grant revocation and cache invalidation therefore commit with the catalog mutation.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PlatformResourceCatalogEffectsImpl implements PlatformResourceCatalogEffects {
  private static final int BATCH = 100;
  private final EntityManager em;
  private final ResourceAuthorizationVersionService authorization;

  /** Binds catalog effects to the identity store and its authorization version writer. */
  public PlatformResourceCatalogEffectsImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization) {
    this.em = em;
    this.authorization = authorization;
  }

  @Override
  public void synchronize(Collection<String> changedResourceIds) {
    synchronize(changedResourceIds, Map.of());
  }

  @Override
  public void synchronize(
      Collection<String> changedResourceIds, Map<String, ParentChange> parentChanges) {
    var affected = platformDescendants(normalize(changedResourceIds));
    Instant now = Instant.now();
    for (String tenant : affectedTenants(affected)) {
      if (!lockTenant(tenant)) {
        continue;
      }
      var ids = mirroredIds(tenant, affected);
      if (ids.isEmpty()) {
        continue;
      }
      var importer = new TenantResourcePlatformImporter(em);
      var plan = importer.prepareCatalogChange(tenant, ids, now, parentChanges);
      boolean changed = !plan.changes().isEmpty();
      for (var proposed : plan.rows().values()) {
        var current =
            em.find(TenantResource.class, new TenantResourceKey(tenant, proposed.getId()));
        if (current != null && !current.getCode().equals(proposed.getCode())) {
          renameTenantCode(tenant, current.getCode(), proposed.getCode());
        }
        // Only a new authoritative platform declaration may end its own formal retirement.
        // Previously revoked ordinary grants remain revoked, including aliases/dependents.
        if (current != null && current.isFormallyOffline() && proposed.isSourceAvailable()) {
          proposed.setFormallyOffline(false);
          proposed.setEnabled(!proposed.isManualDisabled());
          proposed.setUpdatedAt(now);
          changed = true;
        }
      }
      importer.apply(plan);
      em.flush();
      if (changed) {
        authorization.refreshTenant(tenant);
      }
    }
  }

  @Override
  public void retire(Collection<String> removedResourceIds, Collection<String> removedCodes) {
    var affected = normalize(removedResourceIds);
    Instant now = Instant.now();
    for (String tenant : affectedTenants(affected)) {
      if (!lockTenant(tenant)) {
        continue;
      }
      var ids = mirroredIds(tenant, affected);
      if (ids.isEmpty()) {
        continue;
      }
      var dependents = tenantDependents(tenant, ids);
      for (String id : ids) {
        var row = em.find(TenantResource.class, new TenantResourceKey(tenant, id));
        row.setSourceAvailable(false);
        row.setEnabled(false);
        row.setFormallyOffline(true);
        row.setLastSyncedAt(now);
        row.setUpdatedAt(now);
      }
      em.flush();
      for (var batch : batches(dependents)) {
        var codes =
            em.createQuery(
                    "select r.code from TenantResource r where r.tenantId = :tenant and r.id in"
                        + " :ids",
                    String.class)
                .setParameter("tenant", tenant)
                .setParameter("ids", batch)
                .getResultList();
        if (!codes.isEmpty()) {
          mutate(
              RoleResourceGrant.class,
              "r.tenantId = :tenant and r.resourceCode in :codes and r.deletedAt is null",
              Map.of("tenant", tenant, "codes", codes),
              grant -> grant.setDeletedAt(now));
        }
      }
      authorization.refreshTenant(tenant);
    }
    for (var codes : batches(normalize(removedCodes))) {
      mutate(
          RoleResourceGrant.class,
          platform("r") + " and r.resourceCode in :codes and r.deletedAt is null",
          Map.of("codes", codes),
          grant -> grant.setDeletedAt(now));
    }
  }

  @Override
  public void renamePlatformCode(String oldCode, String newCode) {
    if (oldCode == null
        || oldCode.isBlank()
        || newCode == null
        || newCode.isBlank()
        || oldCode.equals(newCode)) {
      return;
    }
    renameReferences(platform("r") + " and r.resourceCode = :old", Map.of("old", oldCode), newCode);
    // Platform templates resolve future allocations by code; issued snapshots keep their stable
    // IDs.
    mutate(
        org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance.class,
        "r.resourceCode = :old",
        Map.of("old", oldCode),
        row -> row.setResourceCode(newCode));
  }

  private void renameTenantCode(String tenant, String oldCode, String newCode) {
    renameReferences(
        "r.tenantId = :tenant and r.resourceCode = :old",
        Map.of("tenant", tenant, "old", oldCode),
        newCode);
  }

  private void renameReferences(String restriction, Map<String, Object> parameters, String code) {
    mutate(RoleResourceGrant.class, restriction, parameters, row -> row.setResourceCode(code));
    mutate(DataScope.class, restriction, parameters, row -> row.setResourceCode(code));
  }

  /** Keep already managed grants coherent; bulk JPQL would leave stale live references behind. */
  private <T extends org.simplepoint.api.base.BaseEntity<String>> void mutate(
      Class<T> type,
      String restriction,
      Map<String, Object> parameters,
      java.util.function.Consumer<T> update) {
    String after = "";
    while (true) {
      var query =
          em.createQuery(
                  "select r from "
                      + type.getSimpleName()
                      + " r where "
                      + restriction
                      + " and r.id > :after order by r.id",
                  type)
              .setParameter("after", after)
              .setMaxResults(BATCH);
      parameters.forEach(query::setParameter);
      var rows = query.getResultList();
      rows.forEach(update);
      em.flush();
      if (rows.size() < BATCH) {
        break;
      }
      after = rows.getLast().getId();
    }
  }

  private static String platform(String alias) {
    return "("
        + alias
        + ".tenantId is null or "
        + alias
        + ".tenantId = '' or "
        + alias
        + ".tenantId = '__platform__')";
  }

  private boolean lockTenant(String tenant) {
    return !em.createQuery(
            "select t.id from Tenant t where t.id = :tenant and t.deletedAt is null", String.class)
        .setParameter("tenant", tenant)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .getResultList()
        .isEmpty();
  }

  private Set<String> affectedTenants(Set<String> sourceIds) {
    var tenants = new TreeSet<String>();
    for (var batch : batches(sourceIds)) {
      String after = "";
      while (true) {
        var page =
            em.createQuery(
                    "select distinct r.tenantId from TenantResource r where r.platformResourceId in"
                        + " :ids and r.tenantId > :after order by r.tenantId",
                    String.class)
                .setParameter("ids", batch)
                .setParameter("after", after)
                .setMaxResults(BATCH)
                .getResultList();
        tenants.addAll(page);
        if (page.size() < BATCH) {
          break;
        }
        after = page.getLast();
      }
    }
    return tenants; // Deterministic tenant lock order, shared with activation and grant writes.
  }

  private Set<String> mirroredIds(String tenant, Set<String> sourceIds) {
    var result = new LinkedHashSet<String>();
    for (var batch : batches(sourceIds)) {
      String after = "";
      while (true) {
        var rows =
            em.createQuery(
                    "select r.id as id, r.platformResourceId as source from TenantResource r where"
                        + " r.tenantId = :tenant and r.platformResourceId in :ids and r.id > :after"
                        + " order by r.id",
                    jakarta.persistence.Tuple.class)
                .setParameter("tenant", tenant)
                .setParameter("ids", batch)
                .setParameter("after", after)
                .setMaxResults(BATCH)
                .getResultList();
        for (var row : rows) {
          String id = row.get("id", String.class);
          if (!id.equals(row.get("source", String.class))) {
            throw new org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException(
                org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code
                    .RESOURCE_CONFLICT);
          }
          result.add(id);
        }
        if (rows.size() < BATCH) {
          break;
        }
        after = rows.getLast().get("id", String.class);
      }
    }
    return result;
  }

  private Set<String> platformDescendants(Set<String> seeds) {
    var visited = new LinkedHashSet<>(seeds);
    var pending = seeds;
    while (!pending.isEmpty()) {
      var next = new LinkedHashSet<String>();
      for (var batch : batches(pending)) {
        String after = "";
        while (true) {
          var page =
              em.createQuery(
                      "select r.id from Resource r where r.parentId in :ids and r.id > :after order"
                          + " by r.id",
                      String.class)
                  .setParameter("ids", batch)
                  .setParameter("after", after)
                  .setMaxResults(BATCH)
                  .getResultList();
          for (String id : page) {
            if (visited.add(id)) {
              next.add(id);
            }
          }
          if (page.size() < BATCH) {
            break;
          }
          after = page.getLast();
        }
      }
      pending = next;
    }
    return visited;
  }

  private Set<String> tenantDependents(String tenant, Set<String> seeds) {
    var visited = new LinkedHashSet<>(seeds);
    var pending = seeds;
    while (!pending.isEmpty()) {
      var next = new LinkedHashSet<String>();
      for (var batch : batches(pending)) {
        String after = "";
        while (true) {
          var page =
              em.createQuery(
                      "select r.id from TenantResource r where r.tenantId = :tenant and r.id >"
                          + " :after and (r.parentId in :ids or r.targetResourceId in :ids or"
                          + " r.managedHostResourceId in :ids) order by r.id",
                      String.class)
                  .setParameter("tenant", tenant)
                  .setParameter("ids", batch)
                  .setParameter("after", after)
                  .setMaxResults(BATCH)
                  .getResultList();
          for (String id : page) {
            if (visited.add(id)) {
              next.add(id);
            }
          }
          if (page.size() < BATCH) {
            break;
          }
          after = page.getLast();
        }
      }
      pending = next;
    }
    return visited;
  }

  private static Set<String> normalize(Collection<String> ids) {
    if (ids == null) {
      return Set.of();
    }
    var result = new LinkedHashSet<String>();
    for (String id : ids) {
      if (id != null && !id.isBlank()) {
        result.add(id);
      }
    }
    return result;
  }

  private static List<List<String>> batches(Collection<String> ids) {
    var values = new ArrayList<>(ids);
    var result = new ArrayList<List<String>>();
    for (int offset = 0; offset < values.size(); offset += BATCH) {
      result.add(values.subList(offset, Math.min(offset + BATCH, values.size())));
    }
    return result;
  }
}
