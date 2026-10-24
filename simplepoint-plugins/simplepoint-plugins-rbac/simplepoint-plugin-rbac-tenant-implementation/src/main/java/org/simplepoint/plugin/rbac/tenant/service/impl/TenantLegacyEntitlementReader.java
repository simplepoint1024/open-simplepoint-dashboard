package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance.Acquisition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;

/**
 * Reads legacy eligibility only during explicit adoption; runtime authorization never uses this
 * adapter.
 */
final class TenantLegacyEntitlementReader {
  private static final int MAX_LINKS = 50000;
  private final EntityManager em;

  TenantLegacyEntitlementReader(EntityManager em) {
    this.em = em;
  }

  record Group(
      String code,
      String name,
      Acquisition acquisition,
      Map<String, TenantPackageResourceDefinition> resources) {
    TenantLegacyAdoption.Source summary() {
      boolean namedPackage = acquisition == Acquisition.LEGACY_PACKAGE;
      return new TenantLegacyAdoption.Source(
          namedPackage ? code : null, namedPackage ? name : null, acquisition, resources.size());
    }
  }

  List<Group> read(String tenantId, TenantType type, TenantResourceMode mode) {
    ResourceScopeType scope =
        type == TenantType.PERSONAL ? ResourceScopeType.PERSONAL : ResourceScopeType.TENANT;
    long ambiguousGrants =
        em.createQuery(
                "select count(g) from RoleResourceGrant g, Role role where g.roleId = role.id and"
                    + " role.tenantId = :tenant and g.deletedAt is null and role.deletedAt is null"
                    + " and (g.tenantId is null or g.tenantId <> :tenant)",
                Long.class)
            .setParameter("tenant", tenantId)
            .getSingleResult();
    if (ambiguousGrants != 0) {
      throw new TenantPackageException(Code.RESOURCE_CONFLICT);
    }
    var memberships =
        em.createQuery(
                "select distinct tp.packageCode, r from TenantPackageRelevance tp, "
                    + "PackageApplicationRelevance pa, ApplicationResourceRelevance ar, Resource r "
                    + "where tp.tenantId = :tenant and tp.packageCode = pa.packageCode "
                    + "and pa.applicationCode = ar.applicationCode and ar.resourceCode = r.code "
                    + "and tp.deletedAt is null and pa.deletedAt is null and ar.deletedAt is null "
                    + "and r.deletedAt is null order by tp.packageCode, r.id",
                Object[].class)
            .setParameter("tenant", tenantId)
            .setMaxResults(MAX_LINKS + 1)
            .getResultList();
    if (memberships.size() > MAX_LINKS) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    // Legacy relations expressed ongoing eligibility; stopped sales and configured durations never
    // expired it.
    var packages = new LinkedHashMap<String, Map<String, TenantPackageResourceDefinition>>();
    for (Object[] row : memberships) {
      Resource resource = (Resource) row[1];
      if (matches(resource, scope)) {
        packages
            .computeIfAbsent((String) row[0], ignored -> new LinkedHashMap<>())
            .put(resource.getId(), definition(resource));
      }
    }
    if (packages.size() > 500) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    var groups = new ArrayList<Group>();
    for (var entry : packages.entrySet()) {
      var labels =
          em.createQuery("select p.name from Package p where p.code = :code", String.class)
              .setParameter("code", entry.getKey())
              .setMaxResults(1)
              .getResultList();
      String label =
          labels.isEmpty() || labels.getFirst() == null ? entry.getKey() : labels.getFirst();
      groups.add(
          new Group(
              entry.getKey(), label, Acquisition.LEGACY_PACKAGE, Map.copyOf(entry.getValue())));
    }
    var publicRows =
        em.createQuery(
                "select r from Resource r where r.publicAccess = true "
                    + "and r.deletedAt is null order by r.id",
                Resource.class)
            .setMaxResults(10001)
            .getResultList();
    append(groups, "__legacy_public__", Acquisition.LEGACY_PUBLIC, publicRows, scope, Set.of());
    if (mode == TenantResourceMode.PLATFORM) {
      var held =
          groups.stream()
              .flatMap(group -> group.resources().keySet().stream())
              .collect(java.util.stream.Collectors.toSet());
      var roleRows =
          em.createQuery(
                  "select distinct r from Resource r, RoleResourceGrant g, Role role where"
                      + " g.resourceCode = r.code and g.roleId = role.id and role.tenantId ="
                      + " :tenant and g.tenantId = :tenant and g.deletedAt is null and"
                      + " role.deletedAt is null and r.deletedAt is null order by r.id",
                  Resource.class)
              .setParameter("tenant", tenantId)
              .setMaxResults(10001)
              .getResultList();
      append(groups, "__legacy_role__", Acquisition.LEGACY_ROLE, roleRows, scope, held);
    }
    // Old generated pages ran through private platform hosts that were omitted from grantable
    // application membership.
    // Preserve only an existing active generated page's explicit host dependency, never all module
    // resources.
    var heldIds =
        groups.stream()
            .flatMap(group -> group.resources().keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
    var owners =
        java.util.Arrays.stream(
                org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceOrigin.values())
            .map(org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceOrigin::managedOwner)
            .filter(java.util.Objects::nonNull)
            .toList();
    var hosts =
        em.createQuery(
                "select distinct r from TenantResource page, Resource r where page.tenantId ="
                    + " :tenant and page.managedOwner in :owners and page.managedHostResourceId ="
                    + " r.id and page.type = :pageType and page.enabled = true and"
                    + " page.manualDisabled = false and page.formallyOffline = false and"
                    + " r.deletedAt is null and (r.disabled is null or r.disabled = false) and"
                    + " r.type = :pageType and r.grantable = false and r.component is not null and"
                    + " trim(r.component) <> '' order by r.id",
                Resource.class)
            .setParameter("tenant", tenantId)
            .setParameter("owners", owners)
            .setParameter("pageType", org.simplepoint.security.entity.ResourceType.PAGE)
            .setMaxResults(10001)
            .getResultList();
    append(groups, "__legacy_host__", Acquisition.LEGACY_HOST, hosts, scope, heldIds);
    if (groups.stream().anyMatch(group -> group.resources().size() > 5000)) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    return List.copyOf(groups);
  }

  private static void append(
      List<Group> groups,
      String code,
      Acquisition acquisition,
      List<Resource> rows,
      ResourceScopeType scope,
      Set<String> excluded) {
    if (rows.size() > 10000) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    var resources = new LinkedHashMap<String, TenantPackageResourceDefinition>();
    rows.stream()
        .filter(row -> matches(row, scope) && !excluded.contains(row.getId()))
        .forEach(row -> resources.put(row.getId(), definition(row)));
    if (!resources.isEmpty()) {
      // Synthetic source labels are translated through acquisition metadata, never presented as
      // purchased packages.
      groups.add(new Group(code, code, acquisition, Map.copyOf(resources)));
    }
  }

  private static boolean matches(Resource resource, ResourceScopeType scope) {
    return ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(scope);
  }

  private static TenantPackageResourceDefinition definition(Resource resource) {
    return new TenantPackageResourceDefinition(resource.getCode(), resource.getName());
  }
}
