package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.DataScopePredicates;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

/** Organization-specific read adapters with one trusted ORM scope for rows, counts and pickers. */
@Repository
public class OrganizationScopedReadQuery {
  private final EntityManager em;

  public OrganizationScopedReadQuery(EntityManager em) {
    this.em = em;
  }

  /** A visible forest starts at a row whose parent is outside the same authorized scope. */
  public Page<Organization> tree(String tenantId, String parentId, Map<String, String> filters,
      Pageable page, DataScopeCondition scope) {
    checkPage(page);
    var input = ProjectionQuery.prepare(Organization.class, filters, page);
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(Organization.class);
    var root = data.from(Organization.class);
    var fields = fields(root);
    data.select(root).where(cb.and(base(data, root, tenantId, scope, parentId, true),
        input.predicate(cb, fields)));
    data.orderBy(input.orders(cb, fields, root.get("id")));
    var rows = em.createQuery(data).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(Organization.class);
    count.select(cb.count(counted)).where(cb.and(
        base(count, counted, tenantId, scope, parentId, true),
        input.predicate(cb, fields(counted))));
    return new PageImpl<>(rows, page, em.createQuery(count).getSingleResult());
  }

  /** Count-free organization picker. All clauses, including search, are JPA Criteria predicates. */
  public Slice<Organization> options(String tenantId, String keyword, String parentId,
      String excludeId, boolean flat, Pageable page, DataScopeCondition scope) {
    checkPage(page);
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(Organization.class);
    var root = query.from(Organization.class);
    var predicates = new ArrayList<Predicate>();
    predicates.add(base(query, root, tenantId, scope, parentId, !flat && keyword == null));
    if (excludeId != null) predicates.add(cb.notEqual(root.get("id"), excludeId));
    if (keyword != null) {
      var terms = new ArrayList<Predicate>();
      String escaped = keyword.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
          .replace("%", "\\%").replace("_", "\\_");
      if (QueryCapabilities.readable(Organization.class, "name"))
        terms.add(cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\'));
      if (QueryCapabilities.readable(Organization.class, "code"))
        terms.add(cb.like(cb.lower(root.get("code")), "%" + escaped + "%", '\\'));
      if (terms.isEmpty()) throw new AccessDeniedException("当前字段权限不允许搜索组织");
      predicates.add(cb.or(terms.toArray(Predicate[]::new)));
    }
    query.select(root).where(cb.and(predicates.toArray(Predicate[]::new)));
    if (flat) query.orderBy(cb.asc(root.get("code")), cb.asc(root.get("id")));
    else query.orderBy(cb.asc(cb.coalesce(root.<Integer>get("sort"), Integer.MAX_VALUE)),
        cb.asc(root.get("name")), cb.asc(root.get("code")), cb.asc(root.get("id")));
    List<Organization> fetched = em.createQuery(query).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize() + 1).getResultList();
    boolean hasNext = fetched.size() > page.getPageSize();
    return new SliceImpl<>(hasNext ? fetched.subList(0, page.getPageSize()) : fetched, page, hasNext);
  }

  /** A tree expander is shown only when at least one child is visible under the same rule. */
  public Set<String> visibleParentsWithChildren(String tenantId, Set<String> parentIds,
      DataScopeCondition scope) {
    if (parentIds.isEmpty()) return Set.of();
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(String.class);
    var root = query.from(Organization.class);
    query.select(root.get("parentId")).distinct(true).where(
        cb.and(base(query, root, tenantId, scope, null, false), root.get("parentId").in(parentIds)));
    return new LinkedHashSet<>(em.createQuery(query).getResultList());
  }

  private Predicate base(CriteriaQuery<?> query, Root<Organization> root, String tenantId,
      DataScopeCondition scope, String parentId, boolean level) {
    var cb = em.getCriteriaBuilder();
    var predicates = new ArrayList<Predicate>();
    predicates.add(cb.equal(root.get("tenantId"), tenantId));
    predicates.add(cb.isNull(root.get("deletedAt")));
    var allowed = DataScopePredicates.compile(scope, root, cb, em.getMetamodel());
    if (allowed != null) predicates.add(allowed);
    if (level) {
      if (parentId != null) {
        predicates.add(cb.equal(root.get("parentId"), parentId));
      } else {
        var parentQuery = query.subquery(Integer.class);
        var parent = parentQuery.from(Organization.class);
        var parentPredicates = new ArrayList<Predicate>();
        parentPredicates.add(cb.equal(parent.get("id"), root.get("parentId")));
        parentPredicates.add(cb.equal(parent.get("tenantId"), tenantId));
        parentPredicates.add(cb.isNull(parent.get("deletedAt")));
        var visibleParent = DataScopePredicates.compile(scope, parent, cb, em.getMetamodel());
        if (visibleParent != null) parentPredicates.add(visibleParent);
        parentQuery.select(cb.literal(1)).where(cb.and(parentPredicates.toArray(Predicate[]::new)));
        predicates.add(cb.not(cb.exists(parentQuery)));
      }
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  private static Map<String, jakarta.persistence.criteria.Expression<?>> fields(Root<Organization> root) {
    return Map.of("name", root.get("name"), "code", root.get("code"),
        "parentId", root.get("parentId"), "type", root.get("type"),
        "description", root.get("description"), "sort", root.get("sort"),
        "enabled", root.get("enabled"));
  }

  private static void checkPage(Pageable page) {
    if (page == null || page.isUnpaged() || page.getPageSize() < 1 || page.getPageSize() > 100
        || page.getOffset() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("组织分页范围超限");
    }
  }
}
