package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.query.TablePagination;
import org.springframework.stereotype.Component;

/** Catalog of local entities and trusted metadata published by remote modules. */
@Component
public class FieldScopeCatalog {
  private final EntityManager entityManager;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog tenantResources;

  /** Creates a namespace-aware permission metadata reader. */
  public FieldScopeCatalog(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Lists registered fields in the selected namespace. */
  public Map<String, List<String>> entries() {
    Map<String, List<String>> result =
        RegisteredPermissionPages.tenant() == null ? localEntries() : new LinkedHashMap<>();
    forEachLegacyMetadata(
        json -> {
          var page = org.simplepoint.core.permission.PermissionPageDescriptor.fromJson(json);
          if (page != null) {
            page.models().stream()
                .filter(org.simplepoint.core.permission.PermissionModelDescriptor::fields)
                .forEach(
                    model ->
                        result.merge(
                            model.model(),
                            model.properties().keySet().stream().sorted().toList(),
                            (local, remote) ->
                                java.util.stream.Stream.concat(local.stream(), remote.stream())
                                    .distinct()
                                    .sorted()
                                    .toList()));
          }
          return true;
        });
    return result;
  }

  private Map<String, List<String>> localEntries() {
    Map<String, List<String>> result = new LinkedHashMap<>();
    entityManager.getMetamodel().getEntities().stream()
        .filter(entity -> entity.getJavaType().getPackageName().startsWith("org.simplepoint."))
        .sorted(java.util.Comparator.comparing(entity -> entity.getJavaType().getName()))
        .forEach(
            entity ->
                result.put(
                    entity.getJavaType().getName(),
                    entity.getAttributes().stream()
                        .map(jakarta.persistence.metamodel.Attribute::getName)
                        .sorted()
                        .toList()));
    return result;
  }

  /**
   * Platform legacy names retain their registry; tenant names resolve only through held eligible
   * pages.
   */
  private void forEachLegacyMetadata(java.util.function.Predicate<String> consume) {
    if (RegisteredPermissionPages.tenant() != null) {
      RegisteredPermissionPages.forEachUntil(
          entityManager,
          tenantResources,
          null,
          RegisteredPermissionPages.Projection.EXPRESSION,
          row -> consume.test((String) row[1]));
      return;
    }
    if (entityManager.getMetamodel().getEntities().stream()
        .noneMatch(
            entity -> entity.getJavaType() == org.simplepoint.security.entity.Resource.class)) {
      return;
    }
    String after = null;
    while (true) {
      var query =
          entityManager.createQuery(
              "select r.code,r.permissionMetadata from Resource r where r.deletedAt is null"
                  + " and r.permissionMetadata is not null"
                  + (after == null ? "" : " and r.code>:after")
                  + " order by r.code",
              Object[].class);
      if (after != null) {
        query.setParameter("after", after);
      }
      var rows = query.setMaxResults(100).getResultList();
      for (var row : rows) {
        if (!consume.test((String) row[1])) {
          return;
        }
      }
      if (rows.size() < 100) {
        break;
      }
      after = (String) rows.getLast()[0];
    }
  }

  /**
   * Resolve requested legacy names within the selected namespace, preserving exact-name priority.
   */
  Map<String, String> resolveLegacyFields(Map<String, java.util.Set<String>> requested) {
    if (requested.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> matched = new LinkedHashMap<>();
    if (RegisteredPermissionPages.tenant() == null) {
      localEntries()
          .forEach((model, fields) -> includeRequestedFields(matched, requested, model, fields));
    }
    if (!exactFieldsResolved(matched, requested)) {
      forEachLegacyMetadata(
          json -> {
            var page = org.simplepoint.core.permission.PermissionPageDescriptor.fromJson(json);
            if (page != null) {
              page.models().stream()
                  .filter(org.simplepoint.core.permission.PermissionModelDescriptor::fields)
                  .forEach(
                      model ->
                          includeRequestedFields(
                              matched, requested, model.model(), model.properties().keySet()));
            }
            // Aliases require the full registry unless an exact simple model key overrides every
            // alias candidate.
            return !exactFieldsResolved(matched, requested);
          });
    }
    Map<String, String> canonical = new LinkedHashMap<>();
    requested.forEach(
        (resource, fields) ->
            fields.forEach(
                field -> canonical.put(resource, validateResource(matched, resource, field))));
    return canonical;
  }

  private static boolean exactFieldsResolved(
      Map<String, List<String>> matched, Map<String, java.util.Set<String>> requested) {
    return requested.entrySet().stream()
        .allMatch(
            entry ->
                matched.containsKey(entry.getKey())
                    && matched.get(entry.getKey()).containsAll(entry.getValue()));
  }

  private static void includeRequestedFields(
      Map<String, List<String>> matched,
      Map<String, java.util.Set<String>> requested,
      String model,
      java.util.Collection<String> fields) {
    var wanted = requested.get(model);
    var aliases = requested.get(model.substring(model.lastIndexOf('.') + 1));
    if (wanted == null && aliases == null) {
      return;
    }
    var found =
        fields.stream()
            .filter(
                field ->
                    wanted != null && wanted.contains(field)
                        || aliases != null && aliases.contains(field))
            .toList();
    // Keep even an empty model: it is still a distinct ambiguous simple-name candidate in the
    // legacy registry.
    matched.merge(
        model,
        found,
        (left, right) ->
            java.util.stream.Stream.concat(left.stream(), right.stream()).distinct().toList());
  }

  /**
   * Configuration writes discover canonical fields through actual active page grants, never the
   * legacy global registry.
   */
  void requireAuthorizedFields(Map<String, java.util.Set<String>> requested) {
    if (requested.isEmpty()) {
      return;
    }
    var remaining = new LinkedHashMap<String, java.util.Set<String>>();
    requested.forEach((model, fields) -> remaining.put(model, new java.util.HashSet<>(fields)));
    RegisteredPermissionPages.forEachUntil(
        entityManager,
        tenantResources,
        null,
        RegisteredPermissionPages.Projection.EXPRESSION,
        row -> {
          var page =
              org.simplepoint.core.permission.PermissionPageDescriptor.fromJson((String) row[1]);
          if (page != null) {
            page.models().stream()
                .filter(org.simplepoint.core.permission.PermissionModelDescriptor::fields)
                .forEach(
                    model -> {
                      var fields = remaining.get(model.model());
                      if (fields == null) {
                        return;
                      }
                      fields.removeAll(model.properties().keySet());
                      if (fields.isEmpty()) {
                        remaining.remove(model.model());
                      }
                    });
          }
          return !remaining.isEmpty();
        });
    if (!remaining.isEmpty()) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段未注册或所属页面未授权");
    }
  }

  /** Page codes are selected by users; canonical model names remain an execution detail. */
  public Map<String, org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields>
      pages() {
    return pages(null);
  }

  /** Lists held eligible pages, optionally restricted to one code. */
  public Map<String, org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields>
      pages(String requestedCode) {
    Map<String, org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields> result =
        new java.util.TreeMap<>();
    RegisteredPermissionPages.forEach(
        entityManager,
        tenantResources,
        requestedCode,
        RegisteredPermissionPages.Projection.FIELDS,
        row -> {
          String code = (String) row[0];
          var descriptor =
              org.simplepoint.core.permission.PermissionPageDescriptor.fromJson((String) row[5]);
          if (descriptor == null) {
            return;
          }
          var models =
              descriptor.models().stream()
                  .filter(model -> model.fields() && !model.properties().isEmpty())
                  .toList();
          if (models.isEmpty()) {
            return;
          }
          String title =
              resourceTitle(
                  RegisteredPermissionPages.tenant() == null
                      ? org.simplepoint.security.entity.Resource.class
                      : org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource.class,
                  row[1],
                  row[4],
                  row[2],
                  row[3]);
          result.put(
              code,
              new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields(
                  title, models));
        });
    return result;
  }

  private static String localizedTitle(Object title) {
    if (!(title instanceof String value) || value.isBlank()) {
      return null;
    }
    return value.startsWith("i18n:") ? value : value.startsWith("menu.") ? "i18n:" + value : null;
  }

  /**
   * Project each real source before choosing a fallback; an unrelated hidden field must not hide a
   * readable label.
   */
  private static String resourceTitle(
      Class<?> sourceType, Object alias, Object title, Object name, Object label) {
    var values = new LinkedHashMap<String, Object>();
    values.put("alias", alias);
    values.put("title", localizedTitle(title));
    values.put("name", name);
    values.put("label", label);
    return org.simplepoint.core.jackson.FieldPermissionPolicy.project(sourceType, values)
        .values()
        .stream()
        .filter(value -> value instanceof String text && !text.isBlank())
        .map(Object::toString)
        .findFirst()
        .orElse("");
  }

  private static String sourceText(Class<?> type, String field, Object value) {
    Object projected =
        org.simplepoint.core.jackson.FieldPermissionPolicy.project(
                type, java.util.Collections.singletonMap(field, value))
            .get(field);
    return projected instanceof String text ? text : null;
  }

  /** Validates a legacy model name against the selected directory. */
  public String validateResource(String resource, String field) {
    return validateResource(entries(), resource, field);
  }

  /** Validates a model name and field against an already resolved namespace catalog. */
  public String validateResource(Map<String, List<String>> catalog, String resource, String field) {
    String canonical = resource;
    if (!catalog.containsKey(resource)) {
      var matches =
          catalog.keySet().stream()
              .filter(name -> name.substring(name.lastIndexOf('.') + 1).equals(resource))
              .toList();
      if (matches.size() != 1) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException(
            "未知或有歧义的资源: " + resource);
      }
      canonical = matches.getFirst();
    }
    if (!catalog.get(canonical).contains(field)) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException(
          "资源字段不存在: " + resource + "#" + field);
    }
    return canonical;
  }

  /** Pages authorized field-bearing resources and their structural ancestors. */
  public org.springframework.data.domain.Page<
          org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.ResourceNode>
      nodes(String parentId, org.springframework.data.domain.Pageable pageable) {
    if (pageable == null
        || pageable.isUnpaged()
        || pageable.getPageSize() > TablePagination.MAX_PAGE_SIZE
        || pageable.getOffset() > Integer.MAX_VALUE
        || pageable.getSort().isSorted()) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("分页参数无效");
    }
    var selection = RegisteredPermissionPages.selection(tenantResources, null);
    var allowed = new LinkedHashMap<String, Edge>();
    var pageCodes = new java.util.HashMap<String, String>();
    RegisteredPermissionPages.forEachUntil(
        entityManager,
        selection,
        RegisteredPermissionPages.Projection.TREE,
        row -> {
          var descriptor =
              org.simplepoint.core.permission.PermissionPageDescriptor.fromJson((String) row[3]);
          if (descriptor != null
              && descriptor.models().stream()
                  .anyMatch(model -> model.fields() && !model.properties().isEmpty())) {
            String id = (String) row[0];
            allowed.put(id, new Edge(id, (String) row[1]));
            pageCodes.put(id, (String) row[2]);
          }
          return true;
        });
    expandAncestors(allowed, selection.tenantId());
    if (selection.tenantId() != null) {
      retainCompletePaths(allowed, pageCodes.keySet());
    }
    return pagedNodes(parentId, pageable, allowed, pageCodes, selection.tenantId());
  }

  private record Edge(String id, String parentId) {}

  private static void retainCompletePaths(Map<String, Edge> allowed, java.util.Set<String> seeds) {
    var validity = new java.util.HashMap<String, Boolean>();
    var reachable = new java.util.HashSet<String>();
    for (String seed : seeds) {
      var path = new java.util.LinkedHashSet<String>();
      String cursor = seed;
      while (cursor != null
          && allowed.containsKey(cursor)
          && !validity.containsKey(cursor)
          && path.add(cursor)) {
        cursor = allowed.get(cursor).parentId();
      }
      boolean valid = cursor == null || Boolean.TRUE.equals(validity.get(cursor));
      for (String id : path) {
        validity.put(id, valid);
      }
      if (valid) {
        reachable.addAll(path);
      }
    }
    allowed.keySet().retainAll(reachable);
  }

  private void expandAncestors(Map<String, Edge> allowed, String tenant) {
    var searched = new java.util.HashSet<>(allowed.keySet());
    var frontier =
        allowed.values().stream()
            .map(Edge::parentId)
            .filter(java.util.Objects::nonNull)
            .filter(id -> !searched.contains(id))
            .collect(java.util.stream.Collectors.toSet());
    while (!frontier.isEmpty()) {
      var batch = new java.util.ArrayList<>(frontier);
      searched.addAll(frontier);
      var parents = new java.util.HashSet<String>();
      for (int start = 0; start < batch.size(); start += 100) {
        var query =
            entityManager
                .createQuery(
                    "select r.id,r.parentId from "
                        + (tenant == null ? "Resource" : "TenantResource")
                        + " r"
                        + (tenant == null
                            ? " where r.deletedAt is null and r.disabled=false and r.type in"
                                + " ('GROUP','MODULE','PAGE')"
                            : " where r.tenantId=:tenant and r.enabled=true and"
                                + " r.manualDisabled=false and r.sourceAvailable=true and"
                                + " r.formallyOffline=false")
                        + " and r.id in :ids",
                    Object[].class)
                .setParameter("ids", batch.subList(start, Math.min(start + 100, batch.size())));
        if (tenant != null) {
          query.setParameter("tenant", tenant);
        }
        query
            .setMaxResults(100)
            .getResultList()
            .forEach(
                row -> {
                  String id = (String) row[0];
                  String parent = (String) row[1];
                  allowed.put(id, new Edge(id, parent));
                  if (parent != null && !searched.contains(parent)) {
                    parents.add(parent);
                  }
                });
      }
      frontier = parents;
    }
  }

  private org.springframework.data.domain.Page<
          org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.ResourceNode>
      pagedNodes(
          String parentId,
          org.springframework.data.domain.Pageable pageable,
          Map<String, Edge> allowed,
          Map<String, String> pageCodes,
          String tenant) {
    if (allowed.isEmpty()) {
      return org.springframework.data.domain.Page.empty(pageable);
    }
    boolean roots = parentId == null || parentId.isBlank();
    var candidates =
        allowed.values().stream()
            .filter(
                edge ->
                    roots
                        ? edge.parentId() == null || !allowed.containsKey(edge.parentId())
                        : parentId.equals(edge.parentId()))
            .map(Edge::id)
            .collect(java.util.stream.Collectors.toSet());
    long total = candidates.size();
    if (pageable.getOffset() >= total) {
      return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, total);
    }
    String source = " from " + (tenant == null ? "Resource" : "TenantResource") + " r";
    String where =
        (tenant == null
            ? " where r.deletedAt is null and r.disabled=false"
            : " where r.tenantId=:tenant and r.enabled=true");
    // Small levels use one native SQL page. Large levels seek only thin ID/sort rows and fetch this
    // page's presentation.
    // Keep database null ordering/collation; Java sorting or truncating the grant set changes the
    // directory contract.
    var selected =
        candidates.size() <= 100
            ? null
            : orderedNodeIds(source, where, roots ? null : parentId, candidates, pageable, tenant);
    var query =
        entityManager
            .createQuery(
                "select r.id,r.parentId,r.alias,r.name,r.label,r.title,r.icon"
                    + source
                    + where
                    + " and r.id in :ids order by r.sort,r.id",
                Object[].class)
            .setParameter("ids", selected == null ? candidates : selected);
    if (tenant != null) {
      query.setParameter("tenant", tenant);
    }
    if (selected == null) {
      query.setFirstResult(Math.toIntExact(pageable.getOffset()));
    }
    var parents =
        allowed.values().stream()
            .map(Edge::parentId)
            .filter(allowed::containsKey)
            .collect(java.util.stream.Collectors.toSet());
    var rows =
        query.setMaxResults(pageable.getPageSize()).getResultList().stream()
            .map(
                row -> {
                  String id = (String) row[0];
                  Class<?> sourceType =
                      tenant == null
                          ? org.simplepoint.security.entity.Resource.class
                          : org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource.class;
                  String title = resourceTitle(sourceType, row[2], row[5], row[3], row[4]);
                  return new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService
                      .ResourceNode(
                      id,
                      (String) row[1],
                      title,
                      sourceText(sourceType, "icon", row[6]),
                      pageCodes.get(id),
                      parents.contains(id));
                })
            .toList();
    return new org.springframework.data.domain.PageImpl<>(rows, pageable, total);
  }

  private List<String> orderedNodeIds(
      String source,
      String where,
      String parent,
      java.util.Set<String> candidates,
      org.springframework.data.domain.Pageable pageable,
      String tenant) {
    var selected = new java.util.ArrayList<String>();
    long skipped = 0;
    long required = Math.min(pageable.getPageSize(), candidates.size() - pageable.getOffset());
    String afterId = null;
    Integer afterSort = null;
    boolean nullsFirst = false;
    while (selected.size() < required) {
      String cursor =
          afterId == null
              ? ""
              : afterSort == null
                  ? " and ((r.sort is null and r.id>:afterId)"
                      + (nullsFirst ? " or r.sort is not null)" : ")")
                  : " and (r.sort>:afterSort or (r.sort=:afterSort and r.id>:afterId)"
                      + (!nullsFirst ? " or r.sort is null)" : ")");
      var query =
          entityManager.createQuery(
              "select r.id,r.sort"
                  + source
                  + where
                  + (parent == null ? "" : " and r.parentId=:parent")
                  + cursor
                  + " order by r.sort,r.id",
              Object[].class);
      if (tenant != null) {
        query.setParameter("tenant", tenant);
      }
      if (parent != null) {
        query.setParameter("parent", parent);
      }
      if (afterId != null) {
        query.setParameter("afterId", afterId);
        if (afterSort != null) {
          query.setParameter("afterSort", afterSort);
        }
      }
      var batch = query.setMaxResults(100).getResultList();
      // Infer the actual database's ASC null ordering from its first row; no dialect-specific
      // approximation.
      if (afterId == null && !batch.isEmpty()) {
        nullsFirst = batch.getFirst()[1] == null;
      }
      for (var row : batch) {
        if (candidates.contains((String) row[0])) {
          if (skipped < pageable.getOffset()) {
            skipped++;
          } else {
            selected.add((String) row[0]);
          }
          if (selected.size() == required) {
            return selected;
          }
        }
      }
      if (batch.size() < 100) {
        return selected;
      }
      var last = batch.getLast();
      afterId = (String) last[0];
      afterSort = (Integer) last[1];
    }
    return selected;
  }
}
