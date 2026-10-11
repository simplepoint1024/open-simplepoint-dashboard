package org.simplepoint.core;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.permission.PermissionExpression;

/** Effective alternatives for one page; row predicates always remain within the tenant boundary. */
public record ResourceDataScope(String type, Set<String> departmentIds, boolean includeSelf,
    List<String> expressions) implements Serializable {
  public ResourceDataScope(String type, Set<String> departmentIds, boolean includeSelf) {
    this(type, departmentIds, includeSelf, List.of());
  }
  public ResourceDataScope {
    departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
    expressions = expressions == null ? List.of() : List.copyOf(expressions);
  }
  public DataScopeCondition condition(String department, String owner, AuthorizationContext context) {
    return new DataScopeCondition(type, department, owner, context.getUserId(), departmentIds, includeSelf,
        expressions.stream().map(PermissionExpression::parse).map(node -> PermissionExpression.resolve(node, context)).toList());
  }
  /** Evaluates an already loaded row using the same alternatives as the SQL predicate. */
  public boolean allows(String department, String owner, AuthorizationContext context,
      java.util.function.Function<String, Object> values) {
    if ("ALL".equals(type)) return true;
    if (condition(department, owner, context).getExpressions().stream().anyMatch(node ->
        org.simplepoint.core.permission.PermissionExpressionEvaluator.matches(node, values))) return true;
    Object ownerValue = "SELF".equals(type) || includeSelf ? values.apply(owner) : null;
    boolean self = ownerValue != null && ownerValue.toString().equals(context.getUserId());
    if ("SELF".equals(type)) return self;
    if (Set.of("DEPT", "DEPT_AND_BELOW", "CUSTOM").contains(type == null ? "" : type)) {
      Object departmentValue = values.apply(department);
      return departmentValue != null && departmentIds.contains(departmentValue.toString()) || includeSelf && self;
    }
    return false;
  }

}
