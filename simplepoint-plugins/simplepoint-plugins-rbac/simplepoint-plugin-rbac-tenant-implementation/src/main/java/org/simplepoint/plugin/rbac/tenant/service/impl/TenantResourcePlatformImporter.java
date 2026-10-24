package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

/**
 * Definition-only import inside the caller's tenant-locked identity transaction; it grants no time
 * or roles.
 */
final class TenantResourcePlatformImporter {
  private static final int MAX_NODES = 10000;
  private static final int MAX_DEPTH = 128;
  private final EntityManager em;

  TenantResourcePlatformImporter(EntityManager em) {
    this.em = em;
  }

  /** One definition plan also drives previews; preparing it never mutates managed entities. */
  record Plan(
      Map<String, TenantResource> rows,
      Set<String> additions,
      Set<String> changes,
      int unavailable) {
    Plan {
      rows = Map.copyOf(rows);
      additions = Set.copyOf(additions);
      changes = Set.copyOf(changes);
    }
  }

  void importForActivation(String tenantId, Collection<String> purchasedIds, Instant now) {
    apply(prepare(tenantId, purchasedIds, now, false, null, true));
  }

  Plan prepare(
      String tenantId,
      Collection<String> requestedIds,
      Instant now,
      boolean overwrite,
      Set<String> baselineIds,
      boolean strict) {
    return prepare(tenantId, requestedIds, now, overwrite, baselineIds, strict, false, Map.of());
  }

  private Plan prepare(
      String tenantId,
      Collection<String> requestedIds,
      Instant now,
      boolean overwrite,
      Set<String> baselineIds,
      boolean strict,
      boolean allowCodeChanges,
      Map<
              String,
              org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
                  .ParentChange>
          parentChanges) {
    TenantType tenantType =
        em.createQuery(
                "select t.tenantType from Tenant t "
                    + "where t.id = :tenant and t.deletedAt is null",
                TenantType.class)
            .setParameter("tenant", tenantId)
            .getSingleResult();
    final ResourceScopeType scope =
        tenantType == TenantType.PERSONAL ? ResourceScopeType.PERSONAL : ResourceScopeType.TENANT;
    Map<String, Resource> sources = definitions(requestedIds, strict);
    // Explicit package/baseline membership acquires executable capabilities. A hidden host may
    // deliberately be non-grantable and non-public; acquiring it must not create a role grant.
    if (strict
        && sources.values().stream()
            .anyMatch(
                source ->
                    source.getType() == null
                        || source.getType() == ResourceType.GROUP
                        || source.getType() == ResourceType.MODULE)) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    Set<String> frontier = new HashSet<>(sources.keySet());
    for (int depth = 0; !frontier.isEmpty(); depth++) {
      if (depth >= MAX_DEPTH || sources.size() > MAX_NODES) {
        throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
      }
      Set<String> parents = new HashSet<>();
      for (String id : frontier) {
        String parent = sources.get(id).getParentId();
        if (parent != null && !sources.containsKey(parent)) {
          parents.add(parent);
        }
      }
      Map<String, Resource> ancestors = definitions(parents, strict);
      sources.putAll(ancestors);
      frontier = ancestors.keySet();
    }
    validateTree(sources);
    Map<String, TenantResource> rows = new LinkedHashMap<>();
    Set<String> additions = new HashSet<>();
    Set<String> changes = new HashSet<>();
    int unavailable = 0;
    for (Resource source : sources.values()) {
      TenantResource current =
          em.find(TenantResource.class, new TenantResourceKey(tenantId, source.getId()));
      validateIdentity(current, source, strict, allowCodeChanges);
      boolean sourceAvailable = availableHierarchy(source, sources, scope);
      if (strict && !sourceAvailable) {
        throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
      }
      TenantResource proposed = copy(current);
      if (current == null) {
        proposed.setTenantId(tenantId);
        proposed.setId(source.getId());
        proposed.setPlatformResourceId(source.getId());
        proposed.setCreatedAt(now);
        additions.add(source.getId());
      }
      if (overwrite) {
        proposed.setLocalModified(false);
      }
      copyDefinition(source, proposed, sourceAvailable);
      var relocation = parentChanges.get(source.getId());
      if (current != null
          && relocation != null
          && Objects.equals(current.getParentId(), relocation.previousParentId())
          && Objects.equals(source.getParentId(), relocation.parentId())) {
        // A translated/customized name does not imply customized placement. Never override an
        // actually moved tenant node, and validate the resulting tenant graph below before writes.
        proposed.setParentId(relocation.parentId());
      }
      if (baselineIds != null) {
        proposed.setBaselineEntitled(baselineIds.contains(source.getId()));
      }
      if (current == null || !values(current).equals(values(proposed))) {
        proposed.setUpdatedAt(now);
        changes.add(source.getId());
      }
      proposed.setLastSyncedAt(now);
      rows.put(source.getId(), proposed);
      if (!sourceAvailable) {
        unavailable++;
      }
    }
    for (String id : requestedIds) {
      if (sources.containsKey(id)) {
        continue;
      }
      TenantResource current = em.find(TenantResource.class, new TenantResourceKey(tenantId, id));
      if (current == null || !current.isPlatformSource()) {
        throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
      }
      TenantResource proposed = copy(current);
      proposed.setSourceAvailable(false);
      proposed.setEnabled(false);
      if (baselineIds != null) {
        proposed.setBaselineEntitled(baselineIds.contains(id));
      }
      if (!values(current).equals(values(proposed))) {
        proposed.setUpdatedAt(now);
        changes.add(id);
      }
      proposed.setLastSyncedAt(now);
      rows.put(id, proposed);
      unavailable++;
    }
    validateTargets(tenantId, rows);
    return new Plan(rows, additions, changes, unavailable);
  }

  /** Only the catalog transaction coordinates a code rename with tenant-local policy references. */
  Plan prepareCatalogChange(String tenantId, Collection<String> ids, Instant now) {
    return prepareCatalogChange(tenantId, ids, now, Map.of());
  }

  Plan prepareCatalogChange(
      String tenantId,
      Collection<String> ids,
      Instant now,
      Map<
              String,
              org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
                  .ParentChange>
          parentChanges) {
    return prepare(tenantId, ids, now, false, null, false, true, parentChanges);
  }

  void apply(Plan plan) {
    for (TenantResource proposed : plan.rows().values()) {
      TenantResource target =
          em.find(
              TenantResource.class,
              new TenantResourceKey(proposed.getTenantId(), proposed.getId()));
      if (plan.additions().contains(proposed.getId())) {
        em.persist(proposed);
      } else {
        org.springframework.beans.BeanUtils.copyProperties(proposed, target);
      }
    }
  }

  private static TenantResource copy(TenantResource current) {
    var result = new TenantResource();
    if (current != null) {
      org.springframework.beans.BeanUtils.copyProperties(current, result);
    }
    return result;
  }

  private static boolean availableHierarchy(
      Resource start, Map<String, Resource> sources, ResourceScopeType scope) {
    Resource cursor = start;
    while (cursor != null) {
      if (!available(cursor)
          || !ResourceScopePolicy.effectiveScopes(cursor.getScopeTypes()).contains(scope)) {
        return false;
      }
      if (cursor.getParentId() == null) {
        return true;
      }
      cursor = sources.get(cursor.getParentId());
    }
    return false;
  }

  private Map<String, Resource> definitions(Collection<String> ids, boolean required) {
    var values = new ArrayList<>(new HashSet<>(ids));
    if (values.size() > MAX_NODES) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    var result = new LinkedHashMap<String, Resource>();
    for (int offset = 0; offset < values.size(); offset += 100) {
      var rows =
          em.createQuery("select r from Resource r where r.id in :ids", Resource.class)
              .setParameter("ids", values.subList(offset, Math.min(offset + 100, values.size())))
              .setMaxResults(100)
              .getResultList();
      rows.forEach(row -> result.put(row.getId(), row));
    }
    if (required && result.size() != values.size()) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    return result;
  }

  private static void validateIdentity(
      TenantResource target, Resource source, boolean strict, boolean allowCodeChanges) {
    if (target != null
        && (!source.getId().equals(target.getPlatformResourceId())
            || target.isManaged()
            || !allowCodeChanges && !Objects.equals(target.getCode(), source.getCode()))) {
      throw new TenantPackageException(Code.RESOURCE_CONFLICT);
    }
    if (strict && target != null && target.isFormallyOffline()) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
  }

  private void validateTargets(String tenantId, Map<String, TenantResource> planned) {
    var parents = new HashMap<String, String>();
    planned.forEach((id, row) -> parents.put(id, row.getParentId()));
    for (TenantResource row : planned.values()) {
      var conflicts =
          em.createQuery(
                  "select r from TenantResource r where r.tenantId = :tenant and r.id <> :id and"
                      + " (r.code = :code or (:path is not null and r.path = :path))",
                  TenantResource.class)
              .setParameter("tenant", tenantId)
              .setParameter("id", row.getId())
              .setParameter("code", row.getCode())
              .setParameter("path", row.getPath())
              .setMaxResults(MAX_NODES + 1)
              .getResultList();
      if (conflicts.size() > MAX_NODES) {
        throw new TenantPackageException(Code.RESOURCE_CONFLICT);
      }
      for (TenantResource conflict : conflicts) {
        TenantResource finalRow = planned.getOrDefault(conflict.getId(), conflict);
        if (row.getCode().equals(finalRow.getCode())
            || row.getPath() != null && row.getPath().equals(finalRow.getPath())) {
          throw new TenantPackageException(Code.RESOURCE_CONFLICT);
        }
      }
      var visited = new HashSet<String>();
      String cursor = row.getId();
      while (cursor != null) {
        if (!visited.add(cursor) || visited.size() > MAX_DEPTH) {
          throw new TenantPackageException(Code.RESOURCE_CONFLICT);
        }
        if (!parents.containsKey(cursor)) {
          TenantResource parent =
              em.find(TenantResource.class, new TenantResourceKey(tenantId, cursor));
          if (parent == null) {
            if (row.isSourceAvailable()) {
              throw new TenantPackageException(Code.RESOURCE_CONFLICT);
            }
            break;
          }
          parents.put(cursor, parent.getParentId());
        }
        cursor = parents.get(cursor);
      }
    }
  }

  private static void validateTree(Map<String, Resource> sources) {
    for (Resource root : sources.values()) {
      Set<String> seen = new HashSet<>();
      Resource cursor = root;
      while (cursor != null) {
        if (!seen.add(cursor.getId()) || seen.size() > MAX_DEPTH) {
          throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
        }
        cursor = cursor.getParentId() == null ? null : sources.get(cursor.getParentId());
      }
    }
  }

  private static boolean available(Resource source) {
    return source.getDeletedAt() == null && !Boolean.TRUE.equals(source.getDisabled());
  }

  private static void copyDefinition(
      Resource source, TenantResource target, boolean sourceAvailable) {
    target.setCode(source.getCode());
    target.setType(source.getType());
    target.setPath(source.getPath());
    target.setComponent(source.getComponent());
    target.setRouteKind(source.getRouteKind());
    target.setAlias(source.getAlias());
    target.setTitle(source.getTitle());
    target.setLabel(source.getLabel());
    target.setPluginId(source.getPluginId());
    target.setScopeTypes(Set.copyOf(ResourceScopePolicy.effectiveScopes(source.getScopeTypes())));
    target.setMethod(source.getMethod());
    target.setPattern(source.getPattern());
    target.setPublicAccess(Boolean.TRUE.equals(source.getPublicAccess()));
    target.setDanger(Boolean.TRUE.equals(source.getDanger()));
    target.setPermissionMetadata(source.getPermissionMetadata());
    target.setGrantable(Boolean.TRUE.equals(source.getGrantable()));
    target.setSourceAvailable(sourceAvailable);
    target.setEnabled(sourceAvailable && !target.isManualDisabled() && !target.isFormallyOffline());
    if (!target.isLocalModified()) {
      target.setName(source.getName());
      // Platform refresh changes its fallback definition, never tenant-owned language overrides.
      if (target.getNameLocalization() != null) {
        var local = target.getNameLocalization();
        target.setNameLocalization(
            new org.simplepoint.api.locale.LocalizedText(
                source.getName(), local.defaultLocale(), local.translations()));
      }
      target.setIcon(source.getIcon());
      target.setSort(source.getSort());
      target.setParentId(source.getParentId());
      target.setDescription(source.getDescription());
    }
  }

  static boolean sameDefinition(TenantResource first, TenantResource second) {
    return values(first).equals(values(second));
  }

  private static List<Object> values(TenantResource value) {
    return Arrays.asList(
        value.getCode(),
        value.getType(),
        value.getPath(),
        value.getComponent(),
        value.getRouteKind(),
        value.getAlias(),
        value.getTitle(),
        value.getLabel(),
        value.getPluginId(),
        value.getScopeTypes(),
        value.getMethod(),
        value.getPattern(),
        value.isPublicAccess(),
        value.isDanger(),
        value.getPermissionMetadata(),
        value.isGrantable(),
        value.isSourceAvailable(),
        value.isEnabled(),
        value.getName(),
        value.getNameLocalization(),
        value.getIcon(),
        value.getSort(),
        value.getParentId(),
        value.getDescription(),
        value.isBaselineEntitled(),
        value.isLocalModified());
  }
}
