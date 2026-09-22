package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Arrays;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryDocument;

/**
 * Bounded AST compiler. Outer mandatory scopes are deliberately outside all user boolean groups.
 */
public final class StructuredQuery {
  private StructuredQuery() {}

  /** Compiles a validated condition tree without altering the caller's outer mandatory scopes. */
  public static Predicate compile(
      CriteriaBuilder cb, AbstractQuery<?> query, Root<?> root, QueryDocument.Node node) {
    return switch (node.op()) {
      case "and" ->
          cb.and(
              node.children().stream()
                  .map(n -> compile(cb, query, root, n))
                  .toArray(Predicate[]::new));
      case "or" ->
          cb.or(
              node.children().stream()
                  .map(n -> compile(cb, query, root, n))
                  .toArray(Predicate[]::new));
      case "not" -> cb.not(compile(cb, query, root, node.children().getFirst()));
      case "exists" -> exists(cb, query, root, node);
      default -> {
        QueryCapabilities.require(root.getJavaType(), node.field(), node.op(), false);
        yield QueryPredicates.build(cb, root.get(node.field()), node);
      }
    };
  }

  private static Predicate exists(
      CriteriaBuilder cb, AbstractQuery<?> query, Root<?> root, QueryDocument.Node node) {
    var declaration = QueryCapabilities.declaration(root.getJavaType());
    var relation =
        declaration == null
            ? null
            : Arrays.stream(declaration.relations())
                .filter(r -> r.value().equals(node.field()))
                .findFirst()
                .orElse(null);
    if (query == null
        || relation == null
        || !QueryCapabilities.readable(root.getJavaType(), node.field())
        || QueryCapabilities.declaration(relation.target()) == null) {
      throw new InvalidQueryException("此关联未开放查询");
    }
    var subquery = query.subquery(Integer.class);
    var child = subquery.from(relation.target());
    var predicates = new ArrayList<Predicate>();
    predicates.add(cb.equal(child.get(relation.foreignKey()), root.get(relation.localKey())));
    predicates.add(compile(cb, subquery, child, node.children().getFirst()));
    if (BaseEntityImpl.class.isAssignableFrom(relation.target())) {
      predicates.add(cb.isNull(child.get("deletedAt")));
    }
    var context = AuthorizationContextHolder.getContext();
    if (TenantBaseEntityImpl.class.isAssignableFrom(relation.target())) {
      String tenant = context == null ? null : context.getAttribute("X-Tenant-Id");
      if (tenant != null && !tenant.isBlank()) {
        predicates.add(cb.equal(child.get("tenantId"), tenant.trim()));
      } else if (!AuthorizationScopeGuards.isPlatformAdministrator(context)) {
        predicates.add(cb.disjunction());
      }
    }
    // Correlated targets need their own data scope; Hibernate filters must not be assumed on
    // subqueries.
    var scope = DataScopeContext.get();
    if (scope == null && context != null && !Boolean.TRUE.equals(context.getIsAdministrator())) {
      var resourceScope = context.activeDataScope();
      scope =
          new org.simplepoint.core.datascopeannotation.DataScopeCondition(
              resourceScope == null
                  ? (context.getDataScopeType() == null ? "NONE" : context.getDataScopeType())
                  : resourceScope.type(),
              relation.departmentField(),
              relation.ownerField(),
              context.getUserId(),
              resourceScope == null ? context.getDeptIds() : resourceScope.departmentIds(),
              resourceScope == null ? Boolean.TRUE.equals(context.getDataScopeIncludeSelf())
                  : resourceScope.includeSelf());
    }
    if (scope == null) {
      if (context == null || !Boolean.TRUE.equals(context.getIsAdministrator())) {
        predicates.add(cb.disjunction());
      }
    } else if (!"ALL".equals(scope.getScopeType())) {
      var allowed = new ArrayList<Predicate>();
      boolean departmentScope =
          java.util.Set.of("DEPT", "DEPT_AND_BELOW", "CUSTOM")
              .contains(scope.getScopeType() == null ? "" : scope.getScopeType());
      if (("SELF".equals(scope.getScopeType()) || (departmentScope && scope.isIncludeSelf()))
          && scope.getUserId() != null) {
        allowed.add(cb.equal(child.get(relation.ownerField()), scope.getUserId()));
      }
      if (departmentScope && scope.getDeptIds() != null && !scope.getDeptIds().isEmpty()) {
        allowed.add(child.get(relation.departmentField()).in(scope.getDeptIds()));
      }
      predicates.add(cb.or(allowed.toArray(Predicate[]::new)));
    }
    subquery.select(cb.literal(1)).where(cb.and(predicates.toArray(Predicate[]::new)));
    return cb.exists(subquery);
  }
}
