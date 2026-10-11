package org.simplepoint.plugin.rbac.resource.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.*;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;

/** Trusted execution-code reads during context construction; no caller, field display, license expansion or entity hydration. */
final class ResourceRuntimeQuery {
  private final EntityManager em;
  ResourceRuntimeQuery(EntityManager em) {this.em = em;}

  List<String> filter(Set<String> codes, AuthorizationScopeType scope, boolean administrator, boolean grantable) {
    if (codes.isEmpty() || scope == null) return List.of();
    var context = context(scope, administrator); var requested = new ArrayList<>(codes); var result = new LinkedHashSet<String>(); var cb = em.getCriteriaBuilder();
    for (int offset = 0; offset < requested.size(); offset += 100) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class);
      query.multiselect(row.get("id").alias("id"), row.get("code").alias("code"), row.get("scopeTypes").alias("scopeTypes"))
          .where(active(cb, row), row.get("code").in(requested.subList(offset, Math.min(offset + 100, requested.size()))),
              grantable ? cb.isTrue(row.get("grantable")) : cb.conjunction()).orderBy(cb.asc(row.get("id")));
      include(em.createQuery(query).setMaxResults(100).getResultList(), context, result);
    }
    return new ArrayList<>(result);
  }

  List<String> all(AuthorizationScopeType scope, boolean administrator) {
    return scope == null ? List.of() : scan(context(scope, administrator), null);
  }

  List<String> flagged(String flag) {
    if (!Set.of("publicAccess", "requireOrgTenant").contains(flag)) throw new IllegalArgumentException("Unsupported execution flag");
    return scan(null, flag);
  }

  private List<String> scan(AuthorizationContext context, String flag) {
    var result = new LinkedHashSet<String>(); var cb = em.getCriteriaBuilder(); String after = null;
    while (true) {
      var query = cb.createTupleQuery(); var row = query.from(Resource.class); var restrictions = new ArrayList<Predicate>(); restrictions.add(active(cb, row));
      if (flag != null) restrictions.add(cb.isTrue(row.get(flag)));
      if (after != null) restrictions.add(cb.greaterThan(row.get("id"), after));
      var columns = new ArrayList<jakarta.persistence.criteria.Selection<?>>(); columns.add(row.get("id").alias("id")); columns.add(row.get("code").alias("code"));
      if (context != null) columns.add(row.get("scopeTypes").alias("scopeTypes"));
      query.multiselect(columns).where(restrictions.toArray(Predicate[]::new)).orderBy(cb.asc(row.get("id")));
      var rows = em.createQuery(query).setMaxResults(100).getResultList(); include(rows, context, result);
      if (rows.size() < 100) return new ArrayList<>(result); after = rows.getLast().get("id", String.class);
    }
  }

  @SuppressWarnings("unchecked") private void include(List<Tuple> rows, AuthorizationContext context, Set<String> result) {
    for (var row : rows) {
      String code = row.get("code", String.class);
      if (code != null && !code.isBlank() && (context == null || ResourceScopePolicy.isAccessible((Set<ResourceScopeType>) row.get("scopeTypes"), context))) result.add(code);
    }
  }

  private Predicate active(CriteriaBuilder cb, Root<Resource> row) {return cb.and(cb.isNull(row.get("deletedAt")), cb.isFalse(row.get("disabled")));}
  private AuthorizationContext context(AuthorizationScopeType scope, boolean administrator) {
    var result = new AuthorizationContext(); result.setScopeType(scope); result.setIsAdministrator(administrator); return result;
  }
}
