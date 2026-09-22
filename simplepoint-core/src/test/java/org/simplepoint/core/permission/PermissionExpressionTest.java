package org.simplepoint.core.permission;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;

class PermissionExpressionTest {
  final Map<String, PermissionModelDescriptor.Field> fields = Map.of(
      "name", new PermissionModelDescriptor.Field("java.lang.String", "name", List.of("equals", "like", "in", "not:in", "is:null"), List.of()),
      "enabled", new PermissionModelDescriptor.Field("java.lang.Boolean", "enabled", List.of("equals"), List.of()),
      "orgId", new PermissionModelDescriptor.Field("java.lang.String", "orgId", List.of("equals", "in"), List.of()));

  @Test void textAndVisualAstHaveIdenticalMeaningAndPreserveEscapedLiterals() {
    var ast = PermissionExpression.validate("name CONTAINS \"a, b\\\"c\" AND\n(enabled = true OR orgId IN $user.organizationIds)", fields);
    assertThat(PermissionExpression.validate(PermissionExpression.format(ast), fields)).isEqualTo(ast);
    var context = new AuthorizationContext(); context.setUserId("u1");
    context.setAttributes(Map.of("X-Org-Dept-Id", "tenant-dept"));
    var resolved = PermissionExpression.resolve(ast, context);
    assertThat(resolved.children().get(1).children().get(1).value().get(0).asText()).isEqualTo("tenant-dept");
  }

  @Test void rejectsSqlUnknownFieldsTypesContextsAndExcessiveNesting() {
    for (String expression : List.of("name = \"x\"; DELETE FROM User", "password = \"secret\"",
        "enabled = \"true\"", "name = $request.header", "name IN []", "name = null",
        "orgId = $user.organizationIds", "(".repeat(17) + "name = \"x\"" + ")".repeat(17))) {
      assertThatThrownBy(() -> PermissionExpression.validate(expression, fields))
          .as(expression).isInstanceOf(InvalidPermissionPolicyException.class);
    }
  }
}
