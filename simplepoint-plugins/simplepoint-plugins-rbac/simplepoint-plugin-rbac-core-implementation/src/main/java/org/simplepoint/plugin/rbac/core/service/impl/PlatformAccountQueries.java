package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PlatformAccountView;
import org.simplepoint.security.entity.PlatformRole;
import org.simplepoint.security.entity.PlatformSecurityAudit;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Bounded platform read queries. The service must recheck persisted platform identity first. */
final class PlatformAccountQueries {
  private static final Set<String> ACCOUNT_TEXT = Set.of("id", "name", "email");
  private static final Set<String> AUDIT_TEXT =
      Set.of("id", "actorId", "targetId", "action", "reason", "beforeState", "afterState");
  private final EntityManager em;

  PlatformAccountQueries(EntityManager em) {
    this.em = em;
  }

  Page<PlatformAccountView> accounts(Map<String, String> attributes, Pageable pageable) {
    validatePage(pageable);
    Map<String, String> filters =
        attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    String keyword = filters.remove("keyword");
    if (keyword != null && !keyword.isBlank()) {
      if (keyword.length() > 100) {
        throw new InvalidQueryException("搜索条件过长");
      }
      for (String field : ACCOUNT_TEXT) {
        QueryCapabilities.require(PlatformAccountView.class, field, "like", false);
      }
    }
    var compiled =
        ProjectionQuery.prepareIgnoringCase(
            PlatformAccountView.class, filters, pageable, ACCOUNT_TEXT);
    CriteriaBuilder cb = em.getCriteriaBuilder();
    var query = cb.createTupleQuery();
    var root = query.from(User.class);
    var fields = accountFields(cb, root);
    query.multiselect(
        fields.get("id"),
        fields.get("name"),
        fields.get("email"),
        fields.get("enabled"),
        fields.get("superAdmin"),
        fields.get("revision"));
    query.where(accountPredicate(cb, root, compiled, fields, keyword));
    var orders = new ArrayList<>(compiled.orders(cb, fields, root.get("id")));
    if (pageable.getSort().isUnsorted()) {
      orders.addFirst(cb.desc(root.get("createdAt")));
    }
    query.orderBy(orders);
    List<Tuple> rows =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();

    var count = cb.createQuery(Long.class);
    var countRoot = count.from(User.class);
    count
        .select(cb.count(countRoot))
        .where(accountPredicate(cb, countRoot, compiled, accountFields(cb, countRoot), keyword));
    long total = em.createQuery(count).getSingleResult();
    Map<String, Set<PlatformRole>> roles =
        pageRoles(rows.stream().map(row -> row.get(0, String.class)).toList());
    var content =
        rows.stream()
            .map(
                row ->
                    new PlatformAccountView(
                        row.get(0, String.class),
                        row.get(1, String.class),
                        row.get(2, String.class),
                        row.get(3, Boolean.class),
                        row.get(4, Boolean.class),
                        roles.getOrDefault(row.get(0, String.class), Set.of()),
                        row.get(5, Long.class)))
            .toList();
    return new PageImpl<>(content, pageable, total);
  }

  private Map<String, Expression<?>> accountFields(CriteriaBuilder cb, Root<User> root) {
    return Map.of(
        "id",
        root.get("id"),
        "name",
        root.get("name"),
        "email",
        root.get("email"),
        "enabled",
        cb.coalesce(root.<Boolean>get("enabled"), false),
        "superAdmin",
        cb.coalesce(root.<Boolean>get("superAdmin"), false),
        "revision",
        cb.coalesce(root.<Long>get("authorizationVersion"), 0L));
  }

  private Predicate accountPredicate(
      CriteriaBuilder cb,
      Root<User> root,
      ProjectionQuery compiled,
      Map<String, Expression<?>> fields,
      String keyword) {
    var result = new ArrayList<Predicate>();
    result.add(cb.isNull(root.get("deletedAt")));
    result.add(compiled.predicate(cb, fields));
    if (keyword != null && !keyword.isBlank()) {
      String text =
          keyword
              .trim()
              .toLowerCase(Locale.ROOT)
              .replace("\\", "\\\\")
              .replace("%", "\\%")
              .replace("_", "\\_");
      result.add(
          cb.or(
              cb.like(cb.lower(root.get("name")), "%" + text + "%", '\\'),
              cb.like(cb.lower(root.get("email")), "%" + text + "%", '\\'),
              cb.equal(root.get("id"), keyword.trim())));
    }
    return cb.and(result.toArray(Predicate[]::new));
  }

  private Map<String, Set<PlatformRole>> pageRoles(List<String> ids) {
    Map<String, Set<PlatformRole>> result = new HashMap<>();
    if (!ids.isEmpty() && QueryCapabilities.readable(PlatformAccountView.class, "roles")) {
      for (Object[] row :
          em.createQuery(
                  "select identity.userId, role from PlatformIdentity identity join identity.roles role "
                      + "where identity.userId in :ids",
                  Object[].class)
              .setParameter("ids", ids)
              .getResultList()) {
        result
            .computeIfAbsent((String) row[0], id -> java.util.EnumSet.noneOf(PlatformRole.class))
            .add((PlatformRole) row[1]);
      }
    }
    return result;
  }

  Page<PlatformSecurityAudit> audits(Map<String, String> filters, Pageable pageable) {
    validatePage(pageable);
    var compiled =
        ProjectionQuery.prepareIgnoringCase(
            PlatformSecurityAudit.class, filters, pageable, AUDIT_TEXT);
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(PlatformSecurityAudit.class);
    var root = query.from(PlatformSecurityAudit.class);
    var fields = auditFields(root);
    query.select(root).where(compiled.predicate(cb, fields));
    var orders = new ArrayList<>(compiled.orders(cb, fields, root.get("id")));
    if (pageable.getSort().isUnsorted()) {
      orders.addFirst(cb.desc(root.get("occurredAt")));
    }
    query.orderBy(orders);
    var rows =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var countRoot = count.from(PlatformSecurityAudit.class);
    count.select(cb.count(countRoot)).where(compiled.predicate(cb, auditFields(countRoot)));
    return new PageImpl<>(rows, pageable, em.createQuery(count).getSingleResult());
  }

  private Map<String, Expression<?>> auditFields(Root<PlatformSecurityAudit> root) {
    Map<String, Expression<?>> result = new HashMap<>();
    AUDIT_TEXT.forEach(field -> result.put(field, root.get(field)));
    result.put("occurredAt", root.get("occurredAt"));
    return result;
  }

  private void validatePage(Pageable pageable) {
    if (pageable.isUnpaged() || pageable.getPageNumber() > 100000 || pageable.getPageSize() > 50) {
      throw new InvalidQueryException("分页范围超限");
    }
  }
}
