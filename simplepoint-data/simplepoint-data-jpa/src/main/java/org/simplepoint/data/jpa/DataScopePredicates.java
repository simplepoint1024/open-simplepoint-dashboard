package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;

/** Compiles a trusted, resolved data rule into a JPA predicate for rows and counts. */
public final class DataScopePredicates {
  private DataScopePredicates() {}

  /** Returns {@code null} only for unrestricted access; unsupported rules deny every row. */
  public static Predicate compile(DataScopeCondition scope, Root<?> root, CriteriaBuilder cb,
      Metamodel metamodel) {
    if (scope == null || "ALL".equals(scope.getScopeType())) return null;
    Set<String> attributes = metamodel.entity(root.getJavaType()).getAttributes().stream()
        .map(attribute -> attribute.getName()).collect(Collectors.toSet());
    return compile(scope, attributes.stream().collect(Collectors.toMap(name -> name, root::get)), cb);
  }

  /** The same compiler also accepts trusted scalar projections for joined pages. */
  public static Predicate compile(DataScopeCondition scope,
      java.util.Map<String, ? extends jakarta.persistence.criteria.Expression<?>> fields, CriteriaBuilder cb) {
    if (scope == null || scope.isAllData()) return null;
    var owner = fields.get(scope.getOwnerField());
    Predicate self = owner == null || scope.getUserId() == null ? cb.disjunction() : cb.equal(owner, scope.getUserId());
    Predicate builtin = switch (scope.getScopeType() == null ? "" : scope.getScopeType()) {
      case "SELF" -> self;
      case "DEPT", "DEPT_AND_BELOW", "CUSTOM" -> {
        var allowed = new ArrayList<Predicate>();
        var department = fields.get(scope.getDeptField());
        if (department != null && scope.getDeptIds() != null && !scope.getDeptIds().isEmpty()) {
          allowed.add(department.in(scope.getDeptIds()));
        }
        if (scope.isIncludeSelf()) allowed.add(self);
        yield allowed.isEmpty() ? cb.disjunction() : cb.or(allowed.toArray(Predicate[]::new));
      }
      default -> cb.disjunction();
    };
    var alternatives = new ArrayList<Predicate>(); alternatives.add(builtin);
    scope.getExpressions().forEach(node -> alternatives.add(expression(node, fields, cb)));
    return cb.or(alternatives.toArray(Predicate[]::new));
  }

  private static Predicate expression(org.simplepoint.core.query.QueryDocument.Node node,
      java.util.Map<String, ? extends jakarta.persistence.criteria.Expression<?>> fields, CriteriaBuilder cb) {
    if (Set.of("and", "or", "not").contains(node.op())) {
      Predicate[] children = node.children().stream().map(child -> expression(child, fields, cb)).toArray(Predicate[]::new);
      return node.op().equals("not") ? cb.not(children[0]) : node.op().equals("and") ? cb.and(children) : cb.or(children);
    }
    var field = fields.get(node.field());
    if (field == null) throw new org.springframework.security.access.AccessDeniedException("权限字段映射已失效: " + node.field());
    if (node.op().startsWith("is:")) return QueryPredicates.build(cb, field, node);
    // A missing context value is SQL UNKNOWN, including under NOT, never an IS NULL match.
    Predicate unknown = cb.equal(cb.nullLiteral(Integer.class), cb.literal(1));
    if (node.value() == null || node.value().isNull()) return unknown;
    if (node.value().isArray()) {
      var nonNull = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      node.value().forEach(value -> { if (!value.isNull()) nonNull.add(value); });
      boolean hasNull = nonNull.size() != node.value().size();
      Predicate included = nonNull.isEmpty() ? cb.disjunction() : QueryPredicates.build(cb, field,
          new org.simplepoint.core.query.QueryDocument.Node("in", node.field(), nonNull, java.util.List.of()));
      if (hasNull) included = cb.or(included, unknown);
      return node.op().equals("not:in") ? cb.not(included) : included;
    }
    return QueryPredicates.build(cb, field, node);
  }
}
