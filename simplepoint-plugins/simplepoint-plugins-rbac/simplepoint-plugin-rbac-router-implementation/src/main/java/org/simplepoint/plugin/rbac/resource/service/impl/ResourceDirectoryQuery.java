package org.simplepoint.plugin.rbac.resource.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.*;
import org.springframework.data.domain.*;

/** Scope/license joins are applied before count/page/child flags. Projection never loads permission metadata or all licensed entities. */
final class ResourceDirectoryQuery {
  private static final List<String> COLUMNS = List.of("id", "parentId", "code", "name", "alias", "label", "title", "icon", "type", "grantable", "disabled", "sort", "scopeTypes", "path", "component", "publicAccess", "requireOrgTenant");
  private static final Set<String> SORTABLE = Set.of("id", "sort", "name", "code", "alias", "label", "title", "type", "path", "component", "createdAt", "updatedAt");
  private static final List<String> MANAGEMENT_COLUMNS = java.util.stream.Stream.concat(COLUMNS.stream(),
      List.of("description", "method", "pattern", "routeKind", "danger", "pluginId", "createdAt", "updatedAt", "createdBy", "updatedBy", "createOrgDeptId").stream()).toList();
  private final EntityManager em;
  private final AuthorizationContext context;
  private final String tenant;
  private final boolean administrator;
  private final Set<Class<?>> mappedTypes;
  ResourceDirectoryQuery(EntityManager em, AuthorizationContext context, String tenant) {
    this.em = em; this.context = context; this.tenant = tenant; this.administrator = AuthorizationScopeGuards.isPlatformAdministrator(context);
    this.mappedTypes = em.getMetamodel().getEntities().stream().map(entity -> (Class<?>) entity.getJavaType()).collect(java.util.stream.Collectors.toSet());
  }
  record Result(Page<Resource> page, Set<String> parentsWithChildren) {}
  record AssignedResult(Page<Resource> page, Set<String> parentsWithChildren, Set<String> checkedIds) {}
  private record IdPage(List<String> ids, long total) {}
  record ManagementResult(Page<Resource> roots, List<Resource> descendants) {}

  /** Complete licensed metadata, distinct from a caller's held navigation authorities. */
  List<Resource> allAccessible() {
    var ids = completeSeeds(null, true, false, administrator);
    if (!administrator) includeAncestors(new LinkedHashSet<>(ids), ids);
    return completePresentation(ids, false, administrator);
  }

  /** Runtime navigation uses only supplied held/public codes; licensing never expands this set. */
  List<Resource> navigation(Set<String> codes, boolean allPlatformRoutes) {
    var ids = completeSeeds(allPlatformRoutes ? null : codes, false, allPlatformRoutes, false);
    if (!allPlatformRoutes) includeAncestors(new LinkedHashSet<>(ids), ids);
    return completePresentation(ids, true, false);
  }

  private Set<String> completeSeeds(Set<String> codes, boolean licensed, boolean routes, boolean allowDisabled) {
    var result = new LinkedHashSet<String>(); var cb = em.getCriteriaBuilder();
    var requested = codes == null ? null : new ArrayList<>(codes);
    for (int offset = 0; requested == null || offset < requested.size(); offset += 100) {
      String after = null;
      while (true) {
        var query = cb.createTupleQuery(); var row = query.from(Resource.class); var restrictions = new ArrayList<Predicate>();
        restrictions.add(completeActive(cb, row, allowDisabled));
        if (licensed) restrictions.add(entitled(cb, query, row));
        if (routes) restrictions.add(routeCandidate(cb, row));
        if (requested != null) restrictions.add(row.get("code").in(requested.subList(offset, Math.min(offset + 100, requested.size()))));
        if (after != null) restrictions.add(cb.greaterThan(row.get("id"), after));
        query.multiselect(row.get("id").alias("id"), row.get("scopeTypes").alias("scopeTypes"))
            .where(restrictions.toArray(Predicate[]::new)).orderBy(cb.asc(row.get("id")));
        var rows = em.createQuery(query).setMaxResults(100).getResultList();
        rows.stream().filter(tuple -> detailScope(tuple, false)).map(tuple -> tuple.get("id", String.class)).forEach(result::add);
        if (rows.size() < 100 || requested != null) break; after = rows.getLast().get("id", String.class);
      }
      if (requested == null) break;
    }
    return result;
  }

  /** Ancestors need structural visibility, not a new grant; verify scope before reading their presentation. */
  private List<Resource> completePresentation(Set<String> included, boolean routes, boolean allowDisabled) {
    var result = new ArrayList<Resource>(); var ids = new ArrayList<>(included); var cb = em.getCriteriaBuilder();
    for (int offset = 0; offset < ids.size(); offset += 100) {
      var thin = cb.createTupleQuery(); var row = thin.from(Resource.class);
      thin.multiselect(row.get("id").alias("id"), row.get("scopeTypes").alias("scopeTypes"))
          .where(completeActive(cb, row, allowDisabled), row.get("id").in(ids.subList(offset, Math.min(offset + 100, ids.size()))));
      var readable = em.createQuery(thin).setMaxResults(100).getResultList().stream().filter(tuple -> detailScope(tuple, false))
          .map(tuple -> tuple.get("id", String.class)).toList();
      if (readable.isEmpty()) continue;
      var query = cb.createTupleQuery(); var item = query.from(Resource.class);
      query.multiselect(managementProjection(item)).where(completeActive(cb, item, allowDisabled), item.get("id").in(readable),
          routes ? routeCandidate(cb, item) : cb.conjunction()).orderBy(cb.asc(item.get("id")));
      for (var tuple : em.createQuery(query).setMaxResults(100).getResultList()) if (detailScope(tuple, false)) result.add(managementResource(tuple));
    }
    return result;
  }

  private Predicate completeActive(CriteriaBuilder cb, Root<Resource> row, boolean allowDisabled) {
    return cb.and(cb.isNull(row.get("deletedAt")), allowDisabled ? cb.conjunction() : cb.isFalse(row.get("disabled")));
  }

  private Predicate routeCandidate(CriteriaBuilder cb, Root<Resource> row) {
    return cb.or(cb.isNotNull(row.get("path")), row.get("type").in(ResourceType.GROUP, ResourceType.MODULE));
  }

  /** Bulk identity hydration has no public sort contract; never sort protected labels or load internal model metadata. */
  List<Resource> byCodes(Set<String> codes, boolean authoring) {
    var requested = new ArrayList<>(codes); var result = new LinkedHashMap<String, Resource>(); var cb = em.getCriteriaBuilder();
    for (int offset = 0; offset < requested.size(); offset += 100) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class);
      query.multiselect(row.get("id").alias("id"), row.get("scopeTypes").alias("scopeTypes"))
          .where(detailBoundary(cb, query, row), row.get("code").in(requested.subList(offset, Math.min(offset + 100, requested.size()))));
      var ids = em.createQuery(query).setMaxResults(100).getResultList().stream().filter(tuple -> detailScope(tuple, authoring))
          .map(tuple -> tuple.get("id", String.class)).toList();
      if (ids.isEmpty()) continue;
      var details = cb.createTupleQuery(); var item = details.from(Resource.class);
      details.multiselect(managementProjection(item)).where(detailBoundary(cb, details, item), item.get("id").in(ids)).orderBy(cb.asc(item.get("id")));
      for (var tuple : em.createQuery(details).setMaxResults(100).getResultList()) if (detailScope(tuple, authoring)) {
        var resource = managementResource(tuple); result.put(resource.getId(), resource);
      }
    }
    return new ArrayList<>(result.values());
  }

  private Predicate detailBoundary(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> row) {
    return cb.and(cb.isNull(row.get("deletedAt")), entitled(cb, query, row), administrator ? cb.conjunction() : cb.isFalse(row.get("disabled")));
  }

  @SuppressWarnings("unchecked") private boolean detailScope(Tuple tuple, boolean authoring) {
    // Use the real enum-set converter/policy, including legacy spelling, not Java text/collation approximations.
    return administrator && authoring || ResourceScopePolicy.isAccessible((Set<ResourceScopeType>) tuple.get("scopeTypes"), context);
  }

  ManagementResult management(Map<String, String> attributes, Pageable pageable) {
    var filters = new HashMap<>(attributes == null ? Map.<String, String>of() : attributes);
    // These constraints are server-owned. Public AST predicates still retain their full typed contract.
    filters.remove("parentId"); filters.remove("deletedAt");
    var adapter = ProjectionQuery.prepare(Resource.class, filters, pageable); var cb = em.getCriteriaBuilder();
    var count = cb.createQuery(Long.class); var root = count.from(Resource.class);
    count.select(cb.count(root)).where(level(cb, count, root, null), adapter.predicate(cb, expressions(root)));
    long total = em.createQuery(count).getSingleResult(); var ids = new ArrayList<String>();
    if (pageable.getOffset() < total) {
      var query = cb.createQuery(String.class); var row = query.from(Resource.class);
      query.select(row.get("id")).where(level(cb, query, row, null), adapter.predicate(cb, expressions(row)))
          .orderBy(adapter.orders(cb, expressions(row), row.get("id")));
      while (ids.size() < pageable.getPageSize()) {
        var batch = em.createQuery(query).setFirstResult(Math.toIntExact(pageable.getOffset() + ids.size()))
            .setMaxResults(Math.min(100, pageable.getPageSize() - ids.size())).getResultList();
        ids.addAll(batch); if (batch.size() < 100) break;
      }
    }
    var roots = new ArrayList<Resource>(); var descendants = new ArrayList<Resource>();
    for (int offset = 0; offset < ids.size(); offset += 100) {
      var rootIds = ids.subList(offset, Math.min(offset + 100, ids.size()));
      var details = cb.createTupleQuery(); var row = details.from(Resource.class);
      details.multiselect(managementProjection(row)).where(visible(cb, details, row), row.get("id").in(rootIds));
      roots.addAll(em.createQuery(details).getResultList().stream().map(this::managementResource).toList());
      if (!mapped(ResourceAncestor.class)) continue;
      String after = null;
      while (true) {
        var query = cb.createTupleQuery(); var child = query.from(Resource.class);
        var relation = query.subquery(Integer.class); var edge = relation.from(ResourceAncestor.class);
        relation.select(cb.literal(1)).where(edge.get("ancestorId").in(rootIds), cb.equal(edge.get("childId"), child.get("id")));
        Predicate restriction = cb.and(visible(cb, query, child), cb.exists(relation));
        if (after != null) restriction = cb.and(restriction, cb.greaterThan(child.get("id"), after));
        query.multiselect(managementProjection(child)).where(restriction).orderBy(cb.asc(child.get("id")));
        var batch = em.createQuery(query).setMaxResults(100).getResultList();
        descendants.addAll(batch.stream().map(this::managementResource).toList());
        if (batch.size() < 100) break; after = batch.getLast().get("id", String.class);
      }
    }
    // The management tree builder preserves this native root order, not a Java approximation of database collation.
    var positions = new HashMap<String, Integer>(); for (int index = 0; index < ids.size(); index++) positions.put(ids.get(index), index);
    roots.sort(Comparator.comparingInt(row -> positions.get(row.getId())));
    return new ManagementResult(new PageImpl<>(roots, pageable, total), descendants);
  }

  private Map<String, Expression<?>> expressions(Root<Resource> row) {
    var result = new HashMap<String, Expression<?>>();
    for (var field : Resource.class.getAnnotation(org.simplepoint.core.query.TableQuery.class).value()) result.put(field.value(), row.get(field.value()));
    return result;
  }

  private List<Selection<?>> managementProjection(Root<Resource> row) {
    var result = new ArrayList<Selection<?>>(); for (var column : MANAGEMENT_COLUMNS) result.add(row.get(column).alias(column)); return result;
  }

  private Resource managementResource(Tuple tuple) {
    var row = resource(tuple, false); row.setDescription(tuple.get("description", String.class)); row.setMethod(tuple.get("method", String.class));
    row.setPattern(tuple.get("pattern", String.class)); row.setRouteKind(tuple.get("routeKind", String.class)); row.setDanger(tuple.get("danger", Boolean.class));
    row.setPluginId(tuple.get("pluginId", String.class)); row.setCreatedAt(tuple.get("createdAt", java.time.Instant.class)); row.setUpdatedAt(tuple.get("updatedAt", java.time.Instant.class));
    row.setCreatedBy(tuple.get("createdBy", String.class)); row.setUpdatedBy(tuple.get("updatedBy", String.class)); row.setCreateOrgDeptId(tuple.get("createOrgDeptId", String.class)); return row;
  }

  /** Client snapshots may exceed one page; no query binds the entire snapshot or ancestor closure. */
  AssignedResult assigned(Set<String> codes, String parent, Pageable pageable) {
    validateSort(pageable.getSort());
    if (codes.isEmpty()) return new AssignedResult(Page.empty(pageable), Set.of(), Set.of());
    var edges = selectedEdges(codes); var selected = new LinkedHashSet<>(edges.keySet());
    if (selected.isEmpty()) return new AssignedResult(Page.empty(pageable), Set.of(), Set.of());
    var included = new LinkedHashSet<>(selected); includeAncestors(selected, included);
    loadAncestorEdges(included, edges);
    Set<String> candidates = edges.entrySet().stream().filter(edge -> Objects.equals(parent, edge.getValue()))
        .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var page = candidates.size() <= 100 ? smallAssignedPage(candidates, parent, pageable) : scanAssignedPage(candidates, parent, pageable);
    if (page.ids().isEmpty()) return new AssignedResult(new PageImpl<>(List.of(), pageable, page.total()), Set.of(), selected);
    var cb = em.getCriteriaBuilder(); var query = cb.createTupleQuery(); var row = query.from(Resource.class);
    query.multiselect(projection(cb, query, row)).where(level(cb, query, row, parent), row.get("id").in(page.ids()))
        .orderBy(order(cb, row, pageable.getSort()));
    var resources = em.createQuery(query).getResultList().stream().map(this::resource).toList();
    var parents = new HashSet<String>(edges.values()); parents.retainAll(page.ids());
    return new AssignedResult(new PageImpl<>(resources, pageable, page.total()), parents, selected);
  }

  private Map<String, String> selectedEdges(Set<String> codes) {
    var requested = new ArrayList<>(codes); var result = new LinkedHashMap<String, String>(); var cb = em.getCriteriaBuilder();
    for (int offset = 0; offset < requested.size(); offset += 100) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class);
      query.multiselect(row.get("id").alias("id"), row.get("parentId").alias("parentId")).where(row.get("code").in(requested.subList(offset, Math.min(offset + 100, requested.size()))),
          administrator ? cb.isNull(row.get("deletedAt")) : activeInScope(cb, row), entitled(cb, query, row));
      for (var entry : em.createQuery(query).getResultList()) result.put(entry.get("id", String.class), entry.get("parentId", String.class));
    }
    return result;
  }

  private void includeAncestors(Set<String> selected, Set<String> included) {
    if (!mapped(ResourceAncestor.class)) return;
    var ids = new ArrayList<>(selected); var cb = em.getCriteriaBuilder();
    for (int offset = 0; offset < ids.size(); offset += 100) {
      String after = null;
      while (true) {
        var query = cb.createQuery(String.class); var relation = query.from(ResourceAncestor.class);
        var predicates = new ArrayList<Predicate>(); predicates.add(relation.get("childId").in(ids.subList(offset, Math.min(offset + 100, ids.size()))));
        if (after != null) predicates.add(cb.greaterThan(relation.get("ancestorId"), after));
        query.select(relation.get("ancestorId")).distinct(true).where(predicates.toArray(Predicate[]::new)).orderBy(cb.asc(relation.get("ancestorId")));
        var batch = em.createQuery(query).setMaxResults(100).getResultList(); included.addAll(batch);
        if (batch.size() < 100) break; after = batch.getLast();
      }
    }
  }

  private void loadAncestorEdges(Set<String> included, Map<String, String> edges) {
    var missing = included.stream().filter(id -> !edges.containsKey(id)).toList(); var cb = em.getCriteriaBuilder();
    for (int offset = 0; offset < missing.size(); offset += 100) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class);
      query.multiselect(row.get("id").alias("id"), row.get("parentId").alias("parentId"))
          .where(visible(cb, query, row), row.get("id").in(missing.subList(offset, Math.min(offset + 100, missing.size()))));
      for (var entry : em.createQuery(query).getResultList()) edges.put(entry.get("id", String.class), entry.get("parentId", String.class));
    }
  }

  private Predicate level(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> row, String parent) {
    return cb.and(visible(cb, query, row), parent == null ? cb.isNull(row.get("parentId")) : cb.equal(row.get("parentId"), parent));
  }

  private IdPage smallAssignedPage(Set<String> included, String parent, Pageable pageable) {
    if (included.isEmpty()) return new IdPage(List.of(), 0);
    var cb = em.getCriteriaBuilder(); var count = cb.createQuery(Long.class); var row = count.from(Resource.class);
    count.select(cb.count(row)).where(level(cb, count, row, parent), row.get("id").in(included));
    long total = em.createQuery(count).getSingleResult();
    if (pageable.getOffset() >= total) return new IdPage(List.of(), total);
    if (pageable.getOffset() > Integer.MAX_VALUE) throw new InvalidQueryException("目录分页偏移超过数据库支持范围");
    var query = cb.createQuery(String.class); var root = query.from(Resource.class);
    query.select(root.get("id")).where(level(cb, query, root, parent), root.get("id").in(included)).orderBy(order(cb, root, pageable.getSort()));
    return new IdPage(em.createQuery(query).setFirstResult(Math.toIntExact(pageable.getOffset())).setMaxResults(pageable.getPageSize()).getResultList(), total);
  }

  /** Native composite cursors preserve collation, mixed directions and the database's own null placement. */
  private IdPage scanAssignedPage(Set<String> included, String parent, Pageable pageable) {
    var cb = em.getCriteriaBuilder(); var orders = stableSort(pageable.getSort()); var nullsFirst = new ArrayList<Boolean>();
    String idAlias = "cursor" + java.util.stream.IntStream.range(0, orders.size()).filter(index -> orders.get(index).getProperty().equals("id")).findFirst().orElseThrow();
    for (var sort : orders) {
      var probe = cb.createQuery(Object.class); var row = probe.from(Resource.class);
      probe.select(row.get(sort.getProperty())).where(level(cb, probe, row, parent))
          .orderBy(sort.isAscending() ? cb.asc(row.get(sort.getProperty())) : cb.desc(row.get(sort.getProperty())));
      var first = em.createQuery(probe).setMaxResults(1).getResultList(); nullsFirst.add(!first.isEmpty() && first.getFirst() == null);
    }
    var ids = new ArrayList<String>(); long total = 0; Tuple after = null;
    while (true) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class); var selections = new ArrayList<Selection<?>>();
      // Criteria paths are shared mutable selections: selecting id twice with different aliases mutates both aliases.
      for (int index = 0; index < orders.size(); index++) selections.add(row.get(orders.get(index).getProperty()).alias("cursor" + index));
      Predicate restriction = level(cb, query, row, parent);
      if (after != null) restriction = cb.and(restriction, cursor(cb, row, orders, nullsFirst, after));
      query.multiselect(selections).where(restriction).orderBy(order(cb, row, pageable.getSort()));
      var batch = em.createQuery(query).setMaxResults(100).getResultList();
      for (var entry : batch) if (included.contains(entry.get(idAlias, String.class))) {
        if (total >= pageable.getOffset() && ids.size() < pageable.getPageSize()) ids.add(entry.get(idAlias, String.class)); total++;
      }
      if (batch.size() < 100) return new IdPage(ids, total); after = batch.getLast();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Predicate cursor(CriteriaBuilder cb, Root<Resource> row, List<Sort.Order> orders, List<Boolean> nullsFirst, Tuple after) {
    var branches = new ArrayList<Predicate>(); var prefix = new ArrayList<Predicate>();
    for (int index = 0; index < orders.size(); index++) {
      var sort = orders.get(index); Expression path = row.get(sort.getProperty()); Object value = after.get("cursor" + index);
      Predicate following;
      if (value == null) following = nullsFirst.get(index) ? cb.isNotNull(path) : cb.disjunction();
      else {
        following = sort.isAscending() ? cb.greaterThan(path, (Comparable) value) : cb.lessThan(path, (Comparable) value);
        if (!nullsFirst.get(index)) following = cb.or(following, cb.isNull(path));
      }
      var branch = new ArrayList<>(prefix); branch.add(following); branches.add(cb.and(branch.toArray(Predicate[]::new)));
      prefix.add(value == null ? cb.isNull(path) : cb.equal(path, value));
    }
    return cb.or(branches.toArray(Predicate[]::new));
  }

  private List<Selection<?>> projection(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> row) {
    var selections = new ArrayList<Selection<?>>(); for (var column : COLUMNS) selections.add(row.get(column).alias(column));
    selections.add(cb.<Boolean>selectCase().when(entitled(cb, query, row), true).otherwise(false).alias("directlyEntitled")); return selections;
  }

  List<String> subtreeCodes(String rootId) {
    var access = FieldPermissionPolicy.access(context, Resource.class, "code");
    if ("MASKED".equals(access) || FieldPermissionPolicy.hidden(access)) throw new org.springframework.security.access.AccessDeniedException("资源编码当前不可读取");
    var cb = em.getCriteriaBuilder(); var rootQuery = cb.createQuery(Integer.class); var root = rootQuery.from(Resource.class);
    rootQuery.select(cb.literal(1)).where(cb.equal(root.get("id"), rootId), visible(cb, rootQuery, root));
    if (em.createQuery(rootQuery).setMaxResults(1).getResultList().isEmpty()) return List.of();
    var result = new ArrayList<String>(); String after = null;
    while (true) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class); var scope = new ArrayList<Predicate>();
      scope.add(cb.isNull(row.get("deletedAt"))); scope.add(cb.isFalse(row.get("disabled"))); scope.add(cb.isTrue(row.get("grantable")));
      scope.add(entitled(cb, query, row)); if (!administrator) scope.add(activeInScope(cb, row));
      Predicate descendant = cb.equal(row.get("id"), rootId);
      if (mapped(ResourceAncestor.class)) {
        var relation = query.subquery(Integer.class); var edge = relation.from(ResourceAncestor.class);
        relation.select(cb.literal(1)).where(cb.equal(edge.get("ancestorId"), rootId), cb.equal(edge.get("childId"), row.get("id")));
        descendant = cb.or(descendant, cb.exists(relation));
      }
      scope.add(descendant); if (after != null) scope.add(cb.greaterThan(row.get("id"), after));
      query.multiselect(row.get("id").alias("id"), row.get("code").alias("code")).where(scope.toArray(Predicate[]::new)).orderBy(cb.asc(row.get("id")));
      var batch = em.createQuery(query).setMaxResults(100).getResultList();
      for (var entry : batch) result.add(entry.get("code", String.class));
      if (batch.size() < 100) return result; after = batch.getLast().get("id", String.class);
    }
  }

  Result children(String parent, String keyword, Pageable pageable) {
    validateSort(pageable.getSort());
    var cb = em.getCriteriaBuilder();
    var count = cb.createQuery(Long.class); var root = count.from(Resource.class);
    count.select(cb.count(root)).where(filters(cb, count, root, parent, keyword));
    long total = em.createQuery(count).getSingleResult();
    if (pageable.getOffset() >= total) return new Result(new PageImpl<>(List.of(), pageable, total), Set.of());
    if (pageable.getOffset() > Integer.MAX_VALUE) throw new InvalidQueryException("目录分页偏移超过数据库支持范围");
    var query = cb.createTupleQuery(); var row = query.from(Resource.class);
    query.multiselect(projection(cb, query, row)).where(filters(cb, query, row, parent, keyword)).orderBy(order(cb, row, pageable.getSort()));
    var resources = em.createQuery(query).setFirstResult(Math.toIntExact(pageable.getOffset())).setMaxResults(pageable.getPageSize())
        .getResultList().stream().map(this::resource).toList();
    var ids = resources.stream().map(Resource::getId).toList();
    var children = cb.createQuery(String.class); var child = children.from(Resource.class);
    children.select(child.get("parentId")).distinct(true).where(visible(cb, children, child), child.get("parentId").in(ids));
    return new Result(new PageImpl<>(resources, pageable, total), new HashSet<>(em.createQuery(children).getResultList()));
  }

  private Predicate filters(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> row, String parent, String keyword) {
    List<Predicate> result = new ArrayList<>(); result.add(visible(cb, query, row));
    if (parent != null) result.add(cb.equal(row.get("parentId"), parent));
    else if (keyword == null) result.add(cb.isNull(row.get("parentId")));
    if (keyword != null) {
      var matches = new ArrayList<Predicate>();
      for (String field : List.of("code", "name", "alias", "label", "title", "path", "component")) {
        var access = FieldPermissionPolicy.access(context, Resource.class, field);
        // Searching unreadable values must not reveal them via result membership/count.
        if ("MASKED".equals(access) || FieldPermissionPolicy.hidden(access)) continue;
        matches.add(cb.like(cb.lower(cb.coalesce(row.get(field), "")), "%" + escape(keyword.toLowerCase(Locale.ROOT)) + "%", '\\'));
      }
      result.add(cb.or(matches.toArray(Predicate[]::new)));
    }
    return cb.and(result.toArray(Predicate[]::new));
  }

  private Predicate visible(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> row) {
    if (administrator) return cb.isNull(row.get("deletedAt")); // Platform catalog authoring includes all scopes and disabled rows.
    Predicate direct = entitled(cb, query, row);
    if (mapped(ResourceAncestor.class)) {
      var sub = query.subquery(Integer.class); var relation = sub.from(ResourceAncestor.class); var descendant = sub.from(Resource.class);
      sub.select(cb.literal(1)).where(cb.equal(relation.get("ancestorId"), row.get("id")), cb.equal(relation.get("childId"), descendant.get("id")),
          activeInScope(cb, descendant), entitled(cb, sub, descendant));
      direct = cb.or(direct, cb.and(row.get("type").in(ResourceType.GROUP, ResourceType.MODULE, ResourceType.PAGE), cb.exists(sub)));
    }
    return cb.and(activeInScope(cb, row), direct);
  }

  private Predicate activeInScope(CriteriaBuilder cb, Root<Resource> row) {
    var scopes = new ArrayList<Predicate>(); var values = ResourceScopeType.values();
    // scopeTypes is a stable converted enum set, not an element-collection. This finite expansion matches the domain policy.
    for (int bits = 1; bits < 1 << values.length; bits++) {
      var set = EnumSet.noneOf(ResourceScopeType.class); for (int index = 0; index < values.length; index++) if ((bits & 1 << index) != 0) set.add(values[index]);
      if (ResourceScopePolicy.isAccessible(set, context)) scopes.add(cb.equal(row.get("scopeTypes"), set));
    }
    if (ResourceScopePolicy.isAccessible(null, context)) scopes.add(cb.isNull(row.get("scopeTypes")));
    return cb.and(cb.isNull(row.get("deletedAt")), cb.isFalse(row.get("disabled")), cb.or(scopes.toArray(Predicate[]::new)));
  }

  private Predicate entitled(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> row) {
    if (administrator) return cb.conjunction();
    if (tenant == null || !mapped(TenantPackageRelevance.class) || !mapped(PackageApplicationRelevance.class) || !mapped(ApplicationResourceRelevance.class)) return cb.isTrue(row.get("publicAccess"));
    var sub = query.subquery(Integer.class); var tp = sub.from(TenantPackageRelevance.class); var pa = sub.from(PackageApplicationRelevance.class); var ar = sub.from(ApplicationResourceRelevance.class);
    sub.select(cb.literal(1)).where(cb.equal(tp.get("tenantId"), tenant), cb.equal(tp.get("packageCode"), pa.get("packageCode")),
        cb.equal(pa.get("applicationCode"), ar.get("applicationCode")), cb.equal(ar.get("resourceCode"), row.get("code")),
        cb.isNull(tp.get("deletedAt")), cb.isNull(pa.get("deletedAt")), cb.isNull(ar.get("deletedAt")));
    return cb.or(cb.isTrue(row.get("publicAccess")), cb.exists(sub));
  }

  private boolean mapped(Class<?> type) {return mappedTypes.contains(type);}
  private static String escape(String value) {return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");}
  private void validateSort(Sort requested) {
    if (requested.stream().count() > 3) throw new InvalidQueryException("最多支持三个排序字段");
    for (var sort : requested) {
      String field = sort.getProperty(); var access = FieldPermissionPolicy.access(context, Resource.class, field);
      if (!SORTABLE.contains(field) || "MASKED".equals(access) || FieldPermissionPolicy.hidden(access)) throw new InvalidQueryException("目录排序字段不可用");
    }
  }
  private List<jakarta.persistence.criteria.Order> order(CriteriaBuilder cb, Root<Resource> row, Sort requested) {
    return stableSort(requested).stream().map(sort -> sort.isAscending() ? cb.asc(row.get(sort.getProperty())) : cb.desc(row.get(sort.getProperty()))).toList();
  }
  private List<Sort.Order> stableSort(Sort requested) {
    var result = new ArrayList<Sort.Order>(); var fields = new HashSet<String>();
    for (var sort : requested) if (fields.add(sort.getProperty())) result.add(sort);
    if (fields.isEmpty()) result.add(Sort.Order.asc("sort"));
    var nameAccess = FieldPermissionPolicy.access(context, Resource.class, "name");
    if (!fields.contains("name") && !"MASKED".equals(nameAccess) && !FieldPermissionPolicy.hidden(nameAccess)) result.add(Sort.Order.asc("name"));
    if (!fields.contains("id")) result.add(Sort.Order.asc("id")); return result;
  }

  private Resource resource(Tuple tuple) {return resource(tuple, true);}
  @SuppressWarnings("unchecked") private Resource resource(Tuple tuple, boolean directory) {
    var row = new Resource(); row.setId(tuple.get("id", String.class)); row.setParentId(tuple.get("parentId", String.class));
    row.setCode(tuple.get("code", String.class)); row.setName(tuple.get("name", String.class)); row.setAlias(tuple.get("alias", String.class));
    row.setLabel(tuple.get("label", String.class)); row.setTitle(tuple.get("title", String.class)); row.setIcon(tuple.get("icon", String.class));
    row.setType(tuple.get("type", ResourceType.class)); row.setDisabled(tuple.get("disabled", Boolean.class)); row.setSort(tuple.get("sort", Integer.class));
    row.setScopeTypes((Set<ResourceScopeType>) tuple.get("scopeTypes")); row.setPath(tuple.get("path", String.class)); row.setComponent(tuple.get("component", String.class));
    row.setPublicAccess(tuple.get("publicAccess", Boolean.class)); row.setRequireOrgTenant(tuple.get("requireOrgTenant", Boolean.class));
    row.setGrantable(directory ? Boolean.TRUE.equals(tuple.get("grantable")) && Boolean.TRUE.equals(tuple.get("directlyEntitled")) : tuple.get("grantable", Boolean.class)); return row;
  }
}
