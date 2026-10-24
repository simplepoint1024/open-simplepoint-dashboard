package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantOwnerCapabilities;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceOrigin;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog.AuthorizationSelection;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.security.access.AccessDeniedException;

/**
 * Bounded scalar reads from the tenant's own directory and time balances; never reads platform
 * definitions.
 */
final class TenantResourceReadQuery {
  private static final int BATCH = 100;
  private static final Set<String> MANAGED_OWNERS =
      java.util.Arrays.stream(TenantResourceOrigin.values())
          .map(TenantResourceOrigin::managedOwner)
          .filter(Objects::nonNull)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
  private static final String EDGE =
      "r.id as id, r.parentId as parent, r.code as code, r.platformResourceId as source,"
          + " r.targetResourceId as target, r.managedHostResourceId as host, r.managedOwner as"
          + " managedOwner, r.grantable as grantable, r.type as type, r.scopeTypes as scopes,"
          + " r.publicAccess as publicAccess, case when r.component is not null and"
          + " trim(r.component) <> '' then true else false end as hasComponent";
  private static final String PRESENTATION =
      "r.id as id, r.parentId as parent, r.code as code, r.platformResourceId as source,"
          + " r.targetResourceId as target, r.managedOwner as managedOwner, r.managedKey as"
          + " managedKey, r.managedHostResourceId as managedHostResourceId, r.name as name, r.nameLocalization as nameLocalization, r.alias"
          + " as alias, r.title as title, r.label as label, r.type as type, r.path as path, case"
          + " when r.managedHostResourceId is not null then host.component when r.targetResourceId"
          + " is null then r.component else target.component end as component, case when"
          + " r.targetResourceId is not null and lower(target.routeKind) = 'hidden' then"
          + " target.routeKind else r.routeKind end as routeKind, r.icon as icon, r.sort as sort,"
          + " r.description as description, r.enabled as enabled, r.grantable as grantable,"
          + " r.publicAccess as publicAccess, r.localModified as localModified, r.manualDisabled as"
          + " manualDisabled, r.createdAt as createdAt, r.updatedAt as updatedAt, r.lastSyncedAt as"
          + " lastSyncedAt";
  // Structural ancestors never receive an executable permission or consume package time.
  // publicAccess is intentionally absent: it waives role grants, never the tenant entitlement.
  private static final String AVAILABLE =
      "r.tenantId = :tenant and r.enabled = true and r.manualDisabled = false and r.sourceAvailable"
          + " = true and r.formallyOffline = false and (r.platformResourceId is null or"
          + " (r.grantable = false and r.type in ('GROUP','MODULE')) or r.baselineEntitled = true"
          + " or exists (select e.id from TenantResourceEntitlement e where e.tenantId = r.tenantId"
          + " and e.id = r.id and e.startsAt <= :now and (e.expiresAt is null or e.expiresAt >"
          + " :now)))";
  private final EntityManager em;
  private final String tenant;
  private final Instant now;
  private boolean checked;
  private ResourceScopeType scope;
  private String ownerId;

  TenantResourceReadQuery(EntityManager em, String tenant) {
    this(em, tenant, Instant.now());
  }

  TenantResourceReadQuery(EntityManager em, String tenant, Instant now) {
    this.em = em;
    this.tenant = tenant;
    this.now = Objects.requireNonNull(now);
  }

  private record Edge(
      String id,
      String parent,
      String code,
      String source,
      String target,
      String host,
      String managedOwner,
      boolean grantable,
      ResourceType type,
      boolean publicAccess,
      boolean hasComponent) {
    Set<String> dependencies() {
      var ids = new LinkedHashSet<String>();
      if (parent != null) {
        ids.add(parent);
      }
      if (target != null) {
        ids.add(target);
      }
      if (host != null) {
        ids.add(host);
      }
      return ids;
    }
  }

  private record Graph(Map<String, Edge> edges, Set<String> selected) {}

  private boolean ready() {
    if (!checked) {
      checked = true;
      if (tenant != null && !tenant.isBlank()) {
        var types =
            em.createQuery(
                    "select t.tenantType as type, t.ownerId as owner from Tenant t,"
                        + " TenantResourceSetup s where t.id = :tenant and s.tenantId = t.id and"
                        + " t.deletedAt is null and s.state = :ready",
                    Tuple.class)
                .setParameter("tenant", tenant)
                .setParameter("ready", TenantResourceSetup.State.READY)
                .setMaxResults(1)
                .getResultList();
        if (!types.isEmpty()) {
          ownerId = types.getFirst().get("owner", String.class);
          TenantType type = types.getFirst().get("type", TenantType.class);
          scope =
              type == TenantType.ORGANIZATION
                  ? ResourceScopeType.TENANT
                  : type == TenantType.PERSONAL ? ResourceScopeType.PERSONAL : null;
        }
      }
    }
    return scope != null;
  }

  private boolean ownedBy(String actorId) {
    return actorId != null && ready() && actorId.equals(ownerId);
  }

  private static Map<String, Edge> forDelegation(Map<String, Edge> edges) {
    var allowed = new LinkedHashMap<>(edges);
    allowed.values().removeIf(edge -> TenantOwnerCapabilities.contains(edge.code()));
    return allowed;
  }

  void requireReady() {
    if (!ready()) {
      throw new AccessDeniedException("Tenant resource directory is not ready");
    }
  }

  List<TenantResource> activeResources() {
    if (!ready()) {
      return List.of();
    }
    return presentation(eligibleResourceIds());
  }

  AuthorizationSelection authorization(Collection<String> roleCodes, boolean allEntitled) {
    return authorization(roleCodes, allEntitled, null);
  }

  AuthorizationSelection authorization(
      Collection<String> roleCodes, boolean allEntitled, String actorId) {
    requireReady();
    var requested = codes(roleCodes);
    // Include public nodes and dependency edges in the same tenant-only scalar graph.
    boolean owner = ownedBy(actorId);
    var edges = owner ? allEdges() : forDelegation(allEdges());
    var valid = validHierarchy(edges);
    var result = new LinkedHashSet<String>();
    for (String id : valid) {
      Edge edge = edges.get(id);
      boolean executable =
          edge.type() != null
              && edge.type() != ResourceType.GROUP
              && edge.type() != ResourceType.MODULE;
      // Public executable entries may deliberately be non-grantable (for example My Items).
      // Their tenant entitlement and full dependency graph have already been validated.
      if (executable
          && (owner && TenantOwnerCapabilities.contains(edge.code())
              || edge.publicAccess()
              || edge.grantable() && (allEntitled || requested.contains(edge.code())))) {
        result.add(edge.code());
        if (edge.target() != null) {
          result.add(edges.get(edge.target()).code());
        }
      }
    }
    return new AuthorizationSelection(result, nextTransition());
  }

  /** Conservative tenant-wide boundary also notices future starts and expiring dependency nodes. */
  private Instant nextTransition() {
    var row =
        em.createQuery(
                "select min(case when e.startsAt > :now then e.startsAt else null end) as starts,"
                    + " min(case when e.expiresAt > :now then e.expiresAt else null end) as expires"
                    + " from TenantResourceEntitlement e where e.tenantId = :tenant",
                Tuple.class)
            .setParameter("tenant", tenant)
            .setParameter("now", now)
            .getSingleResult();
    Instant starts = row.get("starts", Instant.class);
    Instant expires = row.get("expires", Instant.class);
    return starts == null
        ? expires
        : expires == null || starts.isBefore(expires) ? starts : expires;
  }

  /**
   * Scalar hierarchy shared by bounded owner pickers; no presentation or permission metadata is
   * loaded.
   */
  Set<String> eligibleResourceIds() {
    return ready() ? validHierarchy(allEdges()) : Set.of();
  }

  Set<String> eligibleResourceIds(Collection<String> ids) {
    requireReady();
    var graph = selectedGraph("id", ids);
    var valid = validHierarchy(graph.edges());
    valid.retainAll(graph.selected());
    return valid;
  }

  /** Active tenant-local platform page hosts, without scanning unrelated resources. */
  Set<String> eligibleHostIds(Collection<String> ids) {
    requireReady();
    var graph = selectedGraph("id", ids);
    var valid = validHierarchy(graph.edges());
    return graph.selected().stream()
        .filter(valid::contains)
        .filter(id -> validHost(graph.edges().get(id)))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static boolean validHost(Edge edge) {
    return edge != null
        && edge.source() != null
        && edge.type() == ResourceType.PAGE
        && edge.hasComponent();
  }

  Set<String> roleResourceIds() {
    requireReady();
    var edges = forDelegation(allEdges());
    var valid = validHierarchy(edges);
    var included = new LinkedHashSet<String>();
    for (String id : valid) {
      Edge node = edges.get(id);
      if (node.type() == null
          || node.type() == ResourceType.GROUP
          || node.type() == ResourceType.MODULE) {
        continue;
      }
      String cursor = id;
      while (cursor != null && included.add(cursor)) {
        cursor = edges.get(cursor).parent();
      }
    }
    return Set.copyOf(included);
  }

  Set<String> authorizationCodes(Collection<String> requested, boolean effectivePlatformCodes) {
    var normalized = codes(requested);
    if (normalized.isEmpty() || !ready()) {
      return Set.of();
    }
    var graph = selectedGraph(normalized);
    var valid = validHierarchy(forDelegation(graph.edges()));
    var result = new LinkedHashSet<String>();
    for (String id : graph.selected()) {
      Edge node = graph.edges().get(id);
      if (!valid.contains(id) || !node.grantable()) {
        continue;
      }
      if (!effectivePlatformCodes) {
        result.add(node.code());
      } else if (node.source() != null) {
        result.add(node.code());
      } else if (node.target() != null) {
        result.add(graph.edges().get(node.target()).code());
      }
    }
    return result;
  }

  Set<String> eligiblePermissionCodes(Collection<String> requested) {
    requireReady();
    var normalized = codes(requested);
    if (normalized.isEmpty()) {
      return Set.of();
    }
    var graph = selectedGraph(normalized);
    var valid = validHierarchy(forDelegation(graph.edges()));
    var result = new LinkedHashSet<String>();
    for (String id : graph.selected()) {
      Edge node = graph.edges().get(id);
      if (valid.contains(id)
          && node.type() != null
          && node.type() != ResourceType.GROUP
          && node.type() != ResourceType.MODULE
          && (node.grantable() || node.publicAccess())) {
        result.add(node.code());
      }
    }
    return result;
  }

  List<TenantResource> navigationResources(Collection<String> requested) {
    return selectedPresentation(requested, false, true, false);
  }

  List<TenantResource> navigationResources(Collection<String> requested, String actorId) {
    return selectedPresentation(requested, false, true, ownedBy(actorId));
  }

  List<TenantResource> selectedResources(Collection<String> requested, String actorId) {
    return selectedPresentation(requested, false, false, ownedBy(actorId));
  }

  List<TenantResource> selectedResources(Collection<String> requested) {
    return selectedPresentation(requested, false, false, false);
  }

  Set<String> managedGrantableCodes(Collection<String> requested) {
    var normalized = codes(requested);
    if (normalized.isEmpty() || !ready()) {
      return Set.of();
    }
    var graph = selectedGraph(normalized);
    return managedCodes(graph.edges(), graph.selected());
  }

  Set<String> activeManagedGrantableCodes() {
    if (!ready()) {
      return Set.of();
    }
    var edges = allEdges();
    return managedCodes(edges, edges.keySet());
  }

  List<TenantResource> managedNavigationResources(Collection<String> requested) {
    return selectedPresentation(requested, true, true, false);
  }

  private Set<String> managedCodes(Map<String, Edge> edges, Set<String> selected) {
    Set<String> valid = validHierarchy(edges);
    var result = new LinkedHashSet<String>();
    for (String id : selected) {
      Edge edge = edges.get(id);
      if (valid.contains(id) && edge.managedOwner() != null && edge.grantable()) {
        result.add(edge.code());
      }
    }
    return result;
  }

  private List<TenantResource> selectedPresentation(
      Collection<String> requested, boolean managedOnly, boolean navigation, boolean owner) {
    var normalized = codes(requested);
    if (normalized.isEmpty() || !ready()) {
      return List.of();
    }
    var graph = selectedGraph(normalized);
    var edges = owner ? graph.edges() : forDelegation(graph.edges());
    var valid = validHierarchy(edges);
    var selected = new LinkedHashSet<>(graph.selected());
    selected.removeIf(
        id -> !valid.contains(id) || managedOnly && edges.get(id).managedOwner() == null);
    if (navigation) {
      var targets =
          selected.stream().map(edges::get).map(Edge::target).filter(Objects::nonNull).toList();
      selected.removeAll(targets);
    }
    var included = new LinkedHashSet<String>();
    var pending = new ArrayDeque<>(selected);
    while (!pending.isEmpty()) {
      String id = pending.removeFirst();
      if (!included.add(id)) {
        continue;
      }
      Edge edge = edges.get(id);
      if (edge.parent() != null) {
        pending.add(edge.parent());
      }
      if (!navigation && edge.target() != null) {
        pending.add(edge.target());
      }
    }
    return presentation(included);
  }

  private static Set<String> codes(Collection<String> requested) {
    return requested == null
        ? Set.of()
        : requested.stream()
            .filter(Objects::nonNull)
            .filter(code -> !code.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private Map<String, Edge> allEdges() {
    var edges = new LinkedHashMap<String, Edge>();
    String after = null;
    while (true) {
      var query =
          em.createQuery(
                  "select "
                      + EDGE
                      + " from TenantResource r where "
                      + AVAILABLE
                      + (after == null ? "" : " and r.id > :after")
                      + " order by r.id",
                  Tuple.class)
              .setParameter("tenant", tenant)
              .setParameter("now", now)
              .setMaxResults(BATCH);
      if (after != null) {
        query.setParameter("after", after);
      }
      var rows = query.getResultList();
      addEdges(edges, rows);
      if (rows.size() < BATCH) {
        return edges;
      }
      after = rows.getLast().get("id", String.class);
    }
  }

  private Graph selectedGraph(Set<String> codes) {
    return selectedGraph("code", codes);
  }

  private Graph selectedGraph(String key, Collection<String> values) {
    var edges = loadEdges(key, values);
    var selected = new LinkedHashSet<>(edges.keySet());
    var checked = new HashSet<>(edges.keySet());
    var pending = dependencies(edges.values(), checked);
    while (!pending.isEmpty()) {
      checked.addAll(pending);
      var loaded = loadEdges("id", pending);
      edges.putAll(loaded);
      pending = dependencies(loaded.values(), checked);
    }
    return new Graph(edges, selected);
  }

  private static Set<String> dependencies(Collection<Edge> edges, Set<String> checked) {
    return edges.stream()
        .flatMap(edge -> edge.dependencies().stream())
        .filter(id -> !checked.contains(id))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private Map<String, Edge> loadEdges(String key, Collection<String> requested) {
    var values = new ArrayList<>(requested);
    var result = new LinkedHashMap<String, Edge>();
    for (int start = 0; start < values.size(); start += BATCH) {
      // key is an internal fixed property, never a user-supplied expression.
      var rows =
          em.createQuery(
                  "select "
                      + EDGE
                      + " from TenantResource r where "
                      + AVAILABLE
                      + " and r."
                      + key
                      + " in :values order by r.id",
                  Tuple.class)
              .setParameter("tenant", tenant)
              .setParameter("now", now)
              .setParameter("values", values.subList(start, Math.min(start + BATCH, values.size())))
              .setMaxResults(BATCH)
              .getResultList();
      addEdges(result, rows);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private void addEdges(Map<String, Edge> edges, List<Tuple> rows) {
    for (Tuple row : rows) {
      String source = row.get("source", String.class);
      String owner = row.get("managedOwner", String.class);
      String target = row.get("target", String.class);
      var type = row.get("type", ResourceType.class);
      boolean grantable = Boolean.TRUE.equals(row.get("grantable", Boolean.class));
      if (source != null
          && (owner != null
              || target != null
              || !ResourceScopePolicy.effectiveScopes((Set<ResourceScopeType>) row.get("scopes"))
                  .contains(scope))) {
        continue;
      }
      if (owner != null && (!MANAGED_OWNERS.contains(owner) || target != null)) {
        continue;
      }
      if (source == null
          && owner == null
          && (target == null
              ? grantable || type != ResourceType.GROUP && type != ResourceType.MODULE
              : type != ResourceType.PAGE)) {
        continue;
      }
      var edge =
          new Edge(
              row.get("id", String.class),
              row.get("parent", String.class),
              row.get("code", String.class),
              source,
              target,
              row.get("host", String.class),
              owner,
              grantable,
              type,
              Boolean.TRUE.equals(row.get("publicAccess", Boolean.class)),
              Boolean.TRUE.equals(row.get("hasComponent", Boolean.class)));
      edges.put(edge.id(), edge);
    }
  }

  /** Linear dependency resolution rejects missing parents/targets and cycles without recursion. */
  private static Set<String> validHierarchy(Map<String, Edge> edges) {
    var remaining = new HashMap<String, Integer>();
    var dependents = new HashMap<String, List<String>>();
    var ready = new ArrayDeque<String>();
    for (Edge edge : edges.values()) {
      if (edge.host() != null && !validHost(edges.get(edge.host()))) {
        continue;
      }
      if (edge.target() != null) {
        Edge target = edges.get(edge.target());
        if (target == null
            || target.source() == null
            || !target.grantable()
            || target.type() != ResourceType.PAGE) {
          continue;
        }
      }
      var dependencies = edge.dependencies();
      if (!edges.keySet().containsAll(dependencies)) {
        continue;
      }
      remaining.put(edge.id(), dependencies.size());
      if (dependencies.isEmpty()) {
        ready.add(edge.id());
      }
      for (String id : dependencies) {
        dependents.computeIfAbsent(id, ignored -> new ArrayList<>()).add(edge.id());
      }
    }
    var valid = new LinkedHashSet<String>();
    while (!ready.isEmpty()) {
      String id = ready.removeFirst();
      valid.add(id);
      for (String child : dependents.getOrDefault(id, List.of())) {
        if (remaining.computeIfPresent(child, (ignored, value) -> value - 1) == 0) {
          ready.add(child);
        }
      }
    }
    return valid;
  }

  private List<TenantResource> presentation(Set<String> included) {
    var ids = new ArrayList<>(included);
    var result = new ArrayList<TenantResource>();
    for (int start = 0; start < ids.size(); start += BATCH) {
      var rows =
          em.createQuery(
                  "select "
                      + PRESENTATION
                      + " from TenantResource r left join TenantResource target"
                      + " on target.tenantId = r.tenantId and target.id = r.targetResourceId"
                      + " left join TenantResource host on host.tenantId = r.tenantId"
                      + " and host.id = r.managedHostResourceId where "
                      + AVAILABLE
                      + " and r.id in :ids order by r.id",
                  Tuple.class)
              .setParameter("tenant", tenant)
              .setParameter("now", now)
              .setParameter("ids", ids.subList(start, Math.min(start + BATCH, ids.size())))
              .setMaxResults(BATCH)
              .getResultList();
      rows.stream().map(this::resource).forEach(result::add);
    }
    return List.copyOf(result);
  }

  private TenantResource resource(Tuple tuple) {
    var row = new TenantResource();
    row.setTenantId(tenant);
    row.setId(tuple.get("id", String.class));
    row.setParentId(tuple.get("parent", String.class));
    row.setCode(tuple.get("code", String.class));
    row.setPlatformResourceId(tuple.get("source", String.class));
    row.setTargetResourceId(tuple.get("target", String.class));
    row.setManagedOwner(tuple.get("managedOwner", String.class));
    row.setCreatedAt(tuple.get("createdAt", java.time.Instant.class));
    row.setLastSyncedAt(tuple.get("lastSyncedAt", java.time.Instant.class));
    row.setManagedKey(tuple.get("managedKey", String.class));
    row.setManagedHostResourceId(tuple.get("managedHostResourceId", String.class));
    row.setName(tuple.get("name", String.class));
    row.setNameLocalization(tuple.get("nameLocalization", org.simplepoint.api.locale.LocalizedText.class));
    row.setAlias(tuple.get("alias", String.class));
    row.setTitle(tuple.get("title", String.class));
    row.setLabel(tuple.get("label", String.class));
    row.setType(tuple.get("type", ResourceType.class));
    row.setPath(tuple.get("path", String.class));
    row.setComponent(tuple.get("component", String.class));
    row.setRouteKind(tuple.get("routeKind", String.class));
    row.setIcon(tuple.get("icon", String.class));
    row.setSort(tuple.get("sort", Integer.class));
    row.setDescription(tuple.get("description", String.class));
    row.setEnabled(Boolean.TRUE.equals(tuple.get("enabled", Boolean.class)));
    row.setGrantable(Boolean.TRUE.equals(tuple.get("grantable", Boolean.class)));
    row.setPublicAccess(Boolean.TRUE.equals(tuple.get("publicAccess", Boolean.class)));
    row.setLocalModified(Boolean.TRUE.equals(tuple.get("localModified", Boolean.class)));
    row.setManualDisabled(Boolean.TRUE.equals(tuple.get("manualDisabled", Boolean.class)));
    row.setUpdatedAt(tuple.get("updatedAt", java.time.Instant.class));
    return row;
  }
}
