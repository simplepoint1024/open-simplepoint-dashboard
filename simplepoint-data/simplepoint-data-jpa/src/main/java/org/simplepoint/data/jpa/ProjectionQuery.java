package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.simplepoint.core.query.FilterCondition;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

/**
 * SQL projection adapter for explicit scalar read-model fields, including joins and COALESCE.
 * Callers provide expressions, mandatory authorization predicates and identical result/count joins.
 */
public final class ProjectionQuery {
  private final Class<?> model;
  private final Pageable pageable;
  private final Set<String> ignoreCase;
  private final List<BiFunction<CriteriaBuilder, Map<String, Expression<?>>, Predicate>> predicates;

  private ProjectionQuery(
      Class<?> model, Map<String, String> filters, Pageable pageable, Set<String> ignoreCase) {
    this.model = model;
    this.pageable = pageable;
    this.ignoreCase = Set.copyOf(ignoreCase);
    for (String field : this.ignoreCase) {
      if (QueryCapabilities.field(model, field).getType() != String.class) {
        throw new IllegalArgumentException("Case-insensitive query fields must be strings");
      }
    }
    var declaration = QueryCapabilities.declaration(model);
    if (declaration == null
        || pageable.isUnpaged()
        || pageable.getPageSize() > declaration.maxPageSize()
        || pageable.getOffset() > Integer.MAX_VALUE
        || pageable.getSort().stream().count() > 3) {
      throw new InvalidQueryException("查询或分页范围超限");
    }
    Set<String> sorted = new java.util.HashSet<>();
    for (var order : pageable.getSort()) {
      if (!sorted.add(order.getProperty())) {
        throw new InvalidQueryException("排序字段不能重复");
      }
      QueryCapabilities.require(model, order.getProperty(), null, true);
    }
    QueryDocument.validateBudget(filters);
    var compiled =
        new ArrayList<BiFunction<CriteriaBuilder, Map<String, Expression<?>>, Predicate>>();
    if (filters != null) {
      filters.forEach(
          (field, value) -> {
            if (Set.of("page", "size", "sort").contains(field)) {
              return;
            }
            if (QueryDocument.PARAMETER.equals(field) || (value != null && value.startsWith("{"))) {
              var node = QueryDocument.parse(value).where();
              if (!QueryDocument.PARAMETER.equals(field)
                  && (!field.equals(node.field()) || !node.children().isEmpty())) {
                throw new InvalidQueryException("无效的列筛选");
              }
              compiled.add(compile(node));
            } else {
              var condition = FilterCondition.parse(value);
              QueryCapabilities.require(model, field, condition.operator(), false);
              var normalized =
                  this.ignoreCase.contains(field) && condition.value() != null
                      ? new FilterCondition(
                          condition.operator(), condition.value().trim().toLowerCase(Locale.ROOT))
                      : condition;
              compiled.add(
                  (cb, expressions) ->
                      QueryPredicates.build(
                          cb,
                          filterExpression(cb, expressions, field),
                          normalized,
                          EscapeCharacter.DEFAULT,
                          100));
            }
          });
    }
    predicates = List.copyOf(compiled);
  }

  /** Validates the public read-model contract before any SQL query is executed. */
  public static ProjectionQuery prepare(
      Class<?> model, Map<String, String> filters, Pageable pageable) {
    return new ProjectionQuery(model, filters, pageable, Set.of());
  }

  /**
   * Preserves domain-defined case-insensitive text matching without changing display or sort
   * values.
   */
  public static ProjectionQuery prepareIgnoringCase(
      Class<?> model, Map<String, String> filters, Pageable pageable, Set<String> fields) {
    return new ProjectionQuery(model, filters, pageable, fields);
  }

  /** ANDs page rules with user filters; tenant and soft-delete boundaries remain mandatory at the caller. */
  public Predicate predicate(CriteriaBuilder cb, Map<String, Expression<?>> expressions) {
    var policy = model.getAnnotation(org.simplepoint.core.permission.PermissionModel.class);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    var scope = context == null ? null : context.activeDataScope();
    Predicate permission = cb.conjunction();
    if (scope != null && policy != null && policy.data() && (policy.page().isBlank()
        || policy.page().equals(org.simplepoint.core.ResourcePolicyContext.get()))) {
      var condition = scope.condition(policy == null ? "createOrgDeptId" : policy.department(),
          policy == null ? "createdBy" : policy.owner(), context);
      var compiled = DataScopePredicates.compile(condition, expressions, cb);
      if (compiled != null) permission = compiled;
    }
    return cb.and(permission, cb.and(
        predicates.stream()
            .map(predicate -> predicate.apply(cb, expressions))
            .toArray(Predicate[]::new)));
  }

  /** Applies SQL NULLS LAST ordering with an explicit unique, server-owned tie breaker. */
  public List<Order> orders(
      CriteriaBuilder cb, Map<String, Expression<?>> expressions, Expression<?> stableId) {
    var result = new ArrayList<Order>();
    for (var order : pageable.getSort()) {
      var value = QueryExpressions.scalar(cb, expression(expressions, order.getProperty()));
      result.add(cb.asc(cb.selectCase().when(cb.isNull(value), 1).otherwise(0)));
      result.add(order.isAscending() ? cb.asc(value) : cb.desc(value));
    }
    result.add(cb.asc(stableId));
    return result;
  }

  private Expression<?> expression(Map<String, Expression<?>> expressions, String field) {
    var expression = expressions.get(field);
    if (expression == null) {
      throw new InvalidQueryException("此字段未开放查询");
    }
    return expression;
  }

  @SuppressWarnings("unchecked")
  private Expression<?> filterExpression(
      CriteriaBuilder cb, Map<String, Expression<?>> expressions, String field) {
    var value = QueryExpressions.scalar(cb, expression(expressions, field));
    return ignoreCase.contains(field) ? cb.lower((Expression<String>) value) : value;
  }

  private com.fasterxml.jackson.databind.JsonNode fold(
      com.fasterxml.jackson.databind.JsonNode value) {
    if (value == null) {
      return null;
    }
    if (value.isTextual()) {
      return com.fasterxml.jackson.databind.node.TextNode.valueOf(
          value.textValue().trim().toLowerCase(Locale.ROOT));
    }
    if (value.isArray()) {
      var values = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      value.forEach(item -> values.add(fold(item)));
      return values;
    }
    return value;
  }

  private BiFunction<CriteriaBuilder, Map<String, Expression<?>>, Predicate> compile(
      QueryDocument.Node node) {
    if (Set.of("and", "or", "not").contains(node.op())) {
      var children = node.children().stream().map(this::compile).toList();
      return (cb, fields) -> {
        Predicate[] values =
            children.stream().map(child -> child.apply(cb, fields)).toArray(Predicate[]::new);
        return node.op().equals("not")
            ? cb.not(values[0])
            : node.op().equals("or") ? cb.or(values) : cb.and(values);
      };
    }
    // Projection adapters expose only declared scalars, never arbitrary relationships or SQL.
    QueryCapabilities.require(model, node.field(), node.op(), false);
    var normalized =
        ignoreCase.contains(node.field())
            ? new QueryDocument.Node(node.op(), node.field(), fold(node.value()), node.children())
            : node;
    return (cb, fields) ->
        QueryPredicates.build(cb, filterExpression(cb, fields, node.field()), normalized);
  }
}
