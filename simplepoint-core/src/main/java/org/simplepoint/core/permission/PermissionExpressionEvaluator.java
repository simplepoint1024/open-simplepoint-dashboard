package org.simplepoint.core.permission;

import java.util.*;
import java.util.function.Function;
import org.simplepoint.core.query.QueryDocument.Node;
import org.simplepoint.core.query.QueryValues;

/** SQL three-valued logic for checking proposed entity writes before persistence. */
public final class PermissionExpressionEvaluator {
  private PermissionExpressionEvaluator() {}
  public static boolean matches(Node node, Function<String, Object> fields) { return Boolean.TRUE.equals(evaluate(node, fields)); }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Boolean evaluate(Node node, Function<String, Object> fields) {
    if (Set.of("and", "or", "not").contains(node.op())) {
      var values = node.children().stream().map(child -> evaluate(child, fields)).toList();
      if (node.op().equals("not")) return values.getFirst() == null ? null : !values.getFirst();
      if (node.op().equals("and")) return values.contains(false) ? false : values.contains(null) ? null : true;
      return values.contains(true) ? true : values.contains(null) ? null : false;
    }
    Object actual = fields.apply(node.field());
    if (node.op().equals("is:null")) return actual == null;
    if (node.op().equals("is:not:null")) return actual != null;
    boolean membership = Set.of("in", "not:in").contains(node.op());
    if (membership && node.value().isArray() && node.value().isEmpty()) return node.op().equals("not:in");
    if (actual == null || node.value() == null || node.value().isNull()) return null;
    if (node.op().contains("like")) {
      boolean found = actual.toString().toLowerCase(Locale.ROOT)
          .contains(node.value().asText().trim().toLowerCase(Locale.ROOT));
      return node.op().equals("like") ? found : !found;
    }
    List<Object> expected = new ArrayList<>(); boolean hasNull = false;
    Iterable<com.fasterxml.jackson.databind.JsonNode> raw = node.value().isArray() ? node.value() : List.of(node.value());
    for (var value : raw) {
      if (value.isNull()) { hasNull = true; continue; }
      expected.add(actual instanceof String ? value.asText() : QueryValues.convert(actual.getClass(), value.asText()));
    }
    if (membership) {
      boolean found = expected.stream().anyMatch(value -> equal(actual, value));
      Boolean result = found ? Boolean.TRUE : hasNull ? null : Boolean.FALSE;
      return result == null ? null : node.op().equals("in") ? result : !result;
    }
    Object value = expected.getFirst();
    return switch (node.op()) {
      case "equals" -> equal(actual, value);
      case "not:equals" -> !equal(actual, value);
      case "than:greater" -> ((Comparable) actual).compareTo(value) > 0;
      case "than:equal:greater" -> ((Comparable) actual).compareTo(value) >= 0;
      case "than:less" -> ((Comparable) actual).compareTo(value) < 0;
      case "than:equal:less" -> ((Comparable) actual).compareTo(value) <= 0;
      default -> throw new InvalidPermissionPolicyException("权限操作符无效");
    };
  }
  private static boolean equal(Object a, Object b) {
    if (a instanceof Number && b instanceof Number) return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
    return Objects.equals(a, b);
  }
}
