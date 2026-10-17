package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

/** Native read adapter, not a catalog/schema provider. Explicit tenant inputs also serve context construction. */
final class TenantResourceReadQuery {
  private static final String EDGE = "r.id as id, r.parentId as parent, r.code as code, r.platformResourceId as source, r.targetResourceId as target, r.managedOwner as managedOwner, r.grantable as grantable";
  private static final String PRESENTATION = "r.id as id, r.parentId as parent, r.code as code, r.platformResourceId as source, r.targetResourceId as target,"
      + " r.managedOwner as managedOwner, r.managedKey as managedKey, r.managedHostResourceId as managedHostResourceId,"
      + " r.name as name, r.type as type, r.path as path, r.component as component, r.routeKind as routeKind, r.icon as icon, r.sort as sort,"
      + " r.description as description, r.enabled as enabled, r.grantable as grantable, r.localModified as localModified, r.manualDisabled as manualDisabled, r.updatedAt as updatedAt";
  private final EntityManager em;
  private final String tenant;
  TenantResourceReadQuery(EntityManager em, String tenant) {this.em = em; this.tenant = tenant;}
  private record Edge(String id, String parent, String code, String source, String target,
      String managedOwner, boolean grantable) {}
  private record Graph(Map<String, Edge> edges, Set<String> selected) {}

  List<TenantResource> activeResources() {
    if (tenant == null || tenant.isBlank()) return List.of();
    var edges = new LinkedHashMap<String, Edge>(); String after = null;
    while (true) {
      var query = em.createQuery("select " + EDGE + " from TenantResource r where r.tenantId = :tenant and r.enabled = true"
          + (after == null ? "" : " and r.id > :after") + " order by r.id", Tuple.class).setParameter("tenant", tenant).setMaxResults(100);
      if (after != null) query.setParameter("after", after);
      var rows = query.getResultList(); rows.stream().map(this::edge).forEach(row -> edges.put(row.id(), row));
      if (rows.size() < 100) break; after = rows.getLast().get("id", String.class);
    }
    return presentation(validHierarchy(edges));
  }

  private List<TenantResource> presentation(Set<String> included) {
    var ids = new ArrayList<>(included); var result = new ArrayList<TenantResource>();
    for (int start = 0; start < ids.size(); start += 100) {
      var rows = em.createQuery("select " + PRESENTATION + " from TenantResource r where r.tenantId = :tenant and r.enabled = true and r.id in :ids order by r.id", Tuple.class)
          .setParameter("tenant", tenant).setParameter("ids", ids.subList(start, Math.min(start + 100, ids.size()))).setMaxResults(100).getResultList();
      rows.stream().map(this::resource).forEach(result::add);
    }
    return List.copyOf(result);
  }

  Set<String> authorizationCodes(Collection<String> requested, boolean effectivePlatformCodes) {
    if (tenant == null || tenant.isBlank() || requested == null || requested.isEmpty()) return Set.of();
    var codes = codes(requested);
    if (codes.isEmpty()) return Set.of();
    var graph = selectedGraph(codes, true); var edges = graph.edges(); var selected = graph.selected();
    Set<String> valid = validHierarchy(edges); var candidates = new LinkedHashSet<String>();
    for (String id : selected) {
      Edge node = edges.get(id); if (!valid.contains(id) || !node.grantable()) continue;
      Edge target = node.source() != null ? node : edges.get(node.target());
      if (target != null && valid.contains(target.id()) && target.source() != null && target.grantable()) candidates.add(target.code());
    }
    Set<String> live = livePlatformCodes(candidates); var result = new LinkedHashSet<String>();
    for (String id : selected) {
      Edge node = edges.get(id); if (!valid.contains(id) || !node.grantable()) continue;
      if (node.managedOwner() != null) {
        if (!effectivePlatformCodes) result.add(node.code());
        continue;
      }
      Edge target = node.source() != null ? node : edges.get(node.target());
      if (target != null && valid.contains(target.id()) && target.source() != null && target.grantable() && live.contains(target.code())) {
        result.add(effectivePlatformCodes ? target.code() : node.code());
      }
    }
    return result;
  }

  List<TenantResource> navigationResources(Collection<String> heldCodes) {
    if (tenant == null || tenant.isBlank() || heldCodes == null || heldCodes.isEmpty()) return List.of();
    var requested = codes(heldCodes); if (requested.isEmpty()) return List.of();
    var graph = selectedGraph(requested, false); var valid = validHierarchy(graph.edges());
    var selected = graph.selected().stream().filter(valid::contains).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var aliased = selected.stream().map(graph.edges()::get).map(Edge::target).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
    selected.removeAll(aliased); var included = new LinkedHashSet<String>();
    for (String id : selected) {
      String cursor = id;
      while (cursor != null && valid.contains(cursor) && included.add(cursor)) cursor = graph.edges().get(cursor).parent();
    }
    return presentation(included);
  }

  List<TenantResource> selectedResources(Collection<String> requested) {
    if (tenant == null || tenant.isBlank() || requested == null || requested.isEmpty()) return List.of();
    var normalized = codes(requested); if (normalized.isEmpty()) return List.of();
    return presentation(validHierarchy(selectedGraph(normalized, true).edges()));
  }

  Set<String> managedGrantableCodes(Collection<String> requested) {
    if (tenant == null || tenant.isBlank() || requested == null || requested.isEmpty()) {
      return Set.of();
    }
    Set<String> codes = codes(requested);
    if (codes.isEmpty()) return Set.of();
    Map<String, Edge> edges = loadManagedEdgesByCodes(codes);
    Set<String> selected = new LinkedHashSet<>(edges.keySet());
    loadManagedAncestors(edges);
    Set<String> valid = validManagedHierarchy(edges);
    return selected.stream()
        .map(edges::get)
        .filter(Objects::nonNull)
        .filter(Edge::grantable)
        .filter(edge -> valid.contains(edge.id()))
        .map(Edge::code)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  Set<String> activeManagedGrantableCodes() {
    if (tenant == null || tenant.isBlank()) return Set.of();
    Map<String, Edge> edges = new LinkedHashMap<>();
    String after = null;
    while (true) {
      var query = em.createQuery("select " + EDGE + " from TenantResource r "
              + "where r.tenantId = :tenant and r.enabled = true "
              + "and r.managedOwner is not null "
              + (after == null ? "" : "and r.id > :after ")
              + "order by r.id", Tuple.class)
          .setParameter("tenant", tenant)
          .setMaxResults(100);
      if (after != null) query.setParameter("after", after);
      List<Tuple> rows = query.getResultList();
      rows.stream().map(this::edge).forEach(edge -> edges.put(edge.id(), edge));
      if (rows.size() < 100) break;
      after = rows.getLast().get("id", String.class);
    }
    Set<String> valid = validManagedHierarchy(edges);
    return edges.values().stream()
        .filter(Edge::grantable)
        .filter(edge -> valid.contains(edge.id()))
        .map(Edge::code)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  List<TenantResource> managedNavigationResources(Collection<String> heldCodes) {
    if (tenant == null || tenant.isBlank() || heldCodes == null || heldCodes.isEmpty()) {
      return List.of();
    }
    Set<String> requested = codes(heldCodes);
    if (requested.isEmpty()) return List.of();
    Map<String, Edge> edges = loadManagedEdgesByCodes(requested);
    Set<String> selected = new LinkedHashSet<>(edges.keySet());
    loadManagedAncestors(edges);
    Set<String> valid = validManagedHierarchy(edges);
    Set<String> included = new LinkedHashSet<>();
    for (String id : selected) {
      String cursor = id;
      while (cursor != null && valid.contains(cursor) && included.add(cursor)) {
        Edge edge = edges.get(cursor);
        cursor = edge == null ? null : edge.parent();
      }
    }
    return presentation(included);
  }

  private Set<String> codes(Collection<String> requested) {
    return requested.stream().filter(Objects::nonNull).filter(code -> !code.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private Graph selectedGraph(Set<String> codes, boolean includeTargets) {
    var edges = loadEdges("code", codes); var selected = new LinkedHashSet<>(edges.keySet());
    var targets = edges.values().stream().filter(node -> includeTargets).map(Edge::target).filter(Objects::nonNull).filter(id -> !edges.containsKey(id))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    edges.putAll(loadEdges("id", targets));
    var checked = new HashSet<>(edges.keySet()); checked.addAll(targets);
    var parents = edges.values().stream().map(Edge::parent).filter(Objects::nonNull).filter(id -> !checked.contains(id))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    while (!parents.isEmpty()) {
      checked.addAll(parents); var next = loadEdges("id", parents); edges.putAll(next);
      parents = next.values().stream().map(Edge::parent).filter(Objects::nonNull).filter(id -> !checked.contains(id))
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    return new Graph(edges, selected);
  }

  private Map<String, Edge> loadEdges(String key, Collection<String> requested) {
    var values = new ArrayList<>(requested); var result = new LinkedHashMap<String, Edge>();
    for (int start = 0; start < values.size(); start += 100) {
      // key is an internal fixed property, never an input expression or user-selected sort.
      var rows = em.createQuery("select " + EDGE + " from TenantResource r where r.tenantId = :tenant and r.enabled = true and r." + key + " in :values order by r.id", Tuple.class)
          .setParameter("tenant", tenant).setParameter("values", values.subList(start, Math.min(start + 100, values.size()))).setMaxResults(100).getResultList();
      rows.stream().map(this::edge).forEach(row -> result.put(row.id(), row));
    }
    return result;
  }

  private Map<String, Edge> loadManagedEdgesByCodes(Collection<String> requested) {
    List<String> values = new ArrayList<>(requested);
    Map<String, Edge> result = new LinkedHashMap<>();
    for (int start = 0; start < values.size(); start += 100) {
      List<Tuple> rows = em.createQuery("select " + EDGE + " from TenantResource r "
              + "where r.tenantId = :tenant and r.enabled = true "
              + "and r.managedOwner is not null and r.code in :values order by r.id", Tuple.class)
          .setParameter("tenant", tenant)
          .setParameter("values", values.subList(start, Math.min(start + 100, values.size())))
          .setMaxResults(100)
          .getResultList();
      rows.stream().map(this::edge).forEach(edge -> result.put(edge.id(), edge));
    }
    return result;
  }

  private void loadManagedAncestors(Map<String, Edge> edges) {
    Set<String> checked = new HashSet<>(edges.keySet());
    Set<String> parents = edges.values().stream().map(Edge::parent)
        .filter(Objects::nonNull)
        .filter(id -> !checked.contains(id))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    while (!parents.isEmpty()) {
      checked.addAll(parents);
      List<String> values = new ArrayList<>(parents);
      Map<String, Edge> loaded = new LinkedHashMap<>();
      for (int start = 0; start < values.size(); start += 100) {
        List<Tuple> rows = em.createQuery("select " + EDGE + " from TenantResource r "
                + "where r.tenantId = :tenant and r.enabled = true "
                + "and r.managedOwner is not null and r.id in :values order by r.id", Tuple.class)
            .setParameter("tenant", tenant)
            .setParameter("values", values.subList(start, Math.min(start + 100, values.size())))
            .setMaxResults(100)
            .getResultList();
        rows.stream().map(this::edge).forEach(edge -> loaded.put(edge.id(), edge));
      }
      edges.putAll(loaded);
      parents = loaded.values().stream().map(Edge::parent)
          .filter(Objects::nonNull)
          .filter(id -> !checked.contains(id))
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
  }

  /** A missing parent is an external platform boundary; missing managed parents stay invalid. */
  private Set<String> validManagedHierarchy(Map<String, Edge> edges) {
    Set<String> missingParents = edges.values().stream()
        .map(Edge::parent)
        .filter(Objects::nonNull)
        .filter(parent -> !edges.containsKey(parent))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> inactiveManagedParents = new HashSet<>();
    List<String> missing = new ArrayList<>(missingParents);
    for (int start = 0; start < missing.size(); start += 100) {
      em.createQuery("select r.id from TenantResource r where r.tenantId = :tenant "
              + "and r.id in :ids and r.managedOwner is not null", String.class)
          .setParameter("tenant", tenant)
          .setParameter("ids", missing.subList(start, Math.min(start + 100, missing.size())))
          .getResultList()
          .forEach(inactiveManagedParents::add);
    }
    Set<String> valid = new LinkedHashSet<>();
    for (Edge start : edges.values()) {
      Set<String> seen = new LinkedHashSet<>();
      Edge cursor = start;
      boolean accepted = true;
      while (cursor != null && cursor.parent() != null) {
        if (!seen.add(cursor.id())) {
          accepted = false;
          break;
        }
        Edge parent = edges.get(cursor.parent());
        if (parent == null) {
          accepted = !inactiveManagedParents.contains(cursor.parent());
          break;
        }
        cursor = parent;
      }
      if (accepted) valid.add(start.id());
    }
    return valid;
  }

  /** Cache each chain's result: complete or selected graphs do not repeatedly walk every ancestor. */
  private Set<String> validHierarchy(Map<String, Edge> edges) {
    var known = new HashMap<String, Boolean>();
    for (String id : edges.keySet()) {
      var path = new ArrayList<String>(); var seen = new HashSet<String>(); String cursor = id; boolean valid = true;
      while (cursor != null) {
        if (known.containsKey(cursor)) {valid = known.get(cursor); break;}
        Edge node = edges.get(cursor);
        if (node == null) {
          Edge child = path.isEmpty() ? null : edges.get(path.getLast());
          valid = child != null && child.managedOwner() != null;
          break;
        }
        if (!seen.add(cursor)) {valid = false; break;}
        path.add(cursor); cursor = node.parent();
      }
      for (String item : path) known.put(item, valid);
    }
    return edges.keySet().stream().filter(id -> Boolean.TRUE.equals(known.get(id)))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  @SuppressWarnings("unchecked")
  private Set<String> livePlatformCodes(Set<String> candidates) {
    var codes = new ArrayList<>(candidates); var result = new LinkedHashSet<String>();
    for (int start = 0; start < codes.size(); start += 100) {
      var rows = em.createQuery("select r.code as code, r.scopeTypes as scopes from Resource r where r.code in :codes"
          + " and r.deletedAt is null and r.disabled = false and (r.publicAccess = true or exists"
          + " (select 1 from TenantPackageRelevance tp, PackageApplicationRelevance pa, ApplicationResourceRelevance ar"
          + " where tp.tenantId = :tenant and tp.packageCode = pa.packageCode and pa.applicationCode = ar.applicationCode"
          + " and ar.resourceCode = r.code and tp.deletedAt is null and pa.deletedAt is null and ar.deletedAt is null))", Tuple.class)
          .setParameter("tenant", tenant).setParameter("codes", codes.subList(start, Math.min(start + 100, codes.size()))).setMaxResults(100).getResultList();
      for (var row : rows) if (ResourceScopePolicy.effectiveScopes((Set<ResourceScopeType>) row.get("scopes")).contains(ResourceScopeType.TENANT)) result.add(row.get("code", String.class));
    }
    return result;
  }

  private Edge edge(Tuple tuple) {
    return new Edge(tuple.get("id", String.class), tuple.get("parent", String.class), tuple.get("code", String.class),
        tuple.get("source", String.class), tuple.get("target", String.class), tuple.get("managedOwner", String.class),
        Boolean.TRUE.equals(tuple.get("grantable", Boolean.class)));
  }
  private TenantResource resource(Tuple tuple) {
    var row = new TenantResource(); row.setTenantId(tenant); row.setId(tuple.get("id", String.class)); row.setParentId(tuple.get("parent", String.class));
    row.setCode(tuple.get("code", String.class)); row.setPlatformResourceId(tuple.get("source", String.class)); row.setTargetResourceId(tuple.get("target", String.class));
    row.setManagedOwner(tuple.get("managedOwner", String.class)); row.setManagedKey(tuple.get("managedKey", String.class));
    row.setManagedHostResourceId(tuple.get("managedHostResourceId", String.class));
    row.setName(tuple.get("name", String.class)); row.setType(tuple.get("type", ResourceType.class)); row.setPath(tuple.get("path", String.class)); row.setComponent(tuple.get("component", String.class));
    row.setRouteKind(tuple.get("routeKind", String.class)); row.setIcon(tuple.get("icon", String.class)); row.setSort(tuple.get("sort", Integer.class)); row.setDescription(tuple.get("description", String.class));
    row.setEnabled(Boolean.TRUE.equals(tuple.get("enabled", Boolean.class))); row.setGrantable(Boolean.TRUE.equals(tuple.get("grantable", Boolean.class)));
    row.setLocalModified(Boolean.TRUE.equals(tuple.get("localModified", Boolean.class))); row.setManualDisabled(Boolean.TRUE.equals(tuple.get("manualDisabled", Boolean.class))); row.setUpdatedAt(tuple.get("updatedAt", java.time.Instant.class)); return row;
  }
}
