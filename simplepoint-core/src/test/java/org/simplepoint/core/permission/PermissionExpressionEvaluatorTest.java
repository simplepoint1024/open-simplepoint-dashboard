package org.simplepoint.core.permission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionExpressionEvaluatorTest {
  @Test void nullableBooleanCombinationsPreserveSqlUnknownWithoutThrowing() {
    var row = Map.<String, Object>of("status", "published");
    for (String expression : java.util.List.of(
        "author = \"reader\" AND status = \"published\"",
        "author = \"reader\" OR status = \"draft\"",
        "NOT (author = \"reader\" AND status = \"published\")",
        "NOT (author = \"reader\" OR status = \"draft\")",
        "author NOT IN [\"reader\"]")) {
      assertFalse(PermissionExpressionEvaluator.matches(PermissionExpression.parse(expression), row::get), expression);
    }
    assertTrue(PermissionExpressionEvaluator.matches(PermissionExpression.parse(
        "author = \"reader\" OR status = \"published\""), row::get));
    assertTrue(PermissionExpressionEvaluator.matches(PermissionExpression.parse(
        "NOT (author = \"reader\" AND status = \"draft\")"), row::get));
  }
}
