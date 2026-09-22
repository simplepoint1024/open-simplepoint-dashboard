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
}
