package org.simplepoint.core.query;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Versioned, bounded query AST. Values remain JSON scalars/arrays, never CSV or executable code.
 */
public record QueryDocument(Node where) {
  public static final String PARAMETER = "$query";
  public static final int MAX_LENGTH = 8192;
  public static final int MAX_NODES = 32;
  public static final int MAX_DEPTH = 4;
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(MAX_LENGTH)
                          .maxNumberLength(100)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  /** A group, scalar predicate or controlled relationship predicate. */
  public record Node(String op, String field, JsonNode value, List<Node> children) {}

  /** Total request budget, including separate header documents and legacy scalar conditions. */
  public static void validateBudget(java.util.Map<String, String> filters) {
    if (filters == null) {
      return;
    }
    int count = 0;
    int length = 0;
    for (var entry : filters.entrySet()) {
      if (entry.getKey() == null) {
        fail();
      }
      if (Set.of("page", "size", "sort", "deletedAt").contains(entry.getKey())) {
        continue;
      }
      String value = entry.getValue();
      length += value == null ? 0 : value.length();
      count += value != null && value.startsWith("{") ? size(parse(value).where()) : 1;
      if (count > MAX_NODES || length > MAX_LENGTH) {
        fail();
      }
    }
  }

  private static int size(Node node) {
    return 1 + node.children().stream().mapToInt(QueryDocument::size).sum();
  }

  /** Parses and validates structure before any database work is performed. */
  public static QueryDocument parse(String json) {
    if (json == null || json.isBlank() || json.length() > MAX_LENGTH) {
      fail();
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      keys(root, Set.of("version", "where"));
      if (!root.path("version").isIntegralNumber()
          || !root.path("version").canConvertToInt()
          || root.path("version").intValue() != 1
          || root.size() != 2) {
        fail();
      }
      return new QueryDocument(node(root.get("where"), 0, new int[] {0}, false));
    } catch (InvalidQueryException error) {
      throw error;
    } catch (Exception error) {
      throw new InvalidQueryException("无效的结构化查询");
    }
  }

  private static Node node(JsonNode n, int depth, int[] count, boolean inRelation) {
    if (depth > MAX_DEPTH || ++count[0] > MAX_NODES || n == null || !n.isObject()) {
      fail();
    }
    String op = n.path("op").asText("");
    if (Set.of("and", "or", "not").contains(op)) {
      keys(n, Set.of("op", "children"));
      JsonNode children = n.path("children");
      if (!children.isArray() || children.isEmpty() || (op.equals("not") && children.size() != 1)) {
        fail();
      }
      List<Node> result = new ArrayList<>();
      children.forEach(child -> result.add(node(child, depth + 1, count, inRelation)));
      return new Node(op, null, null, List.copyOf(result));
    }
    if (op.equals("exists")) {
      keys(n, Set.of("op", "field", "where"));
      if (inRelation) {
        fail();
      }
      return new Node(op, field(n), null, List.of(node(n.get("where"), depth + 1, count, true)));
    }
    keys(n, Set.of("op", "field", "value"));
    if (!FilterCondition.OPERATORS.contains(op)) {
      fail();
    }
    JsonNode value = n.get("value");
    if (op.equals("is:null") || op.equals("is:not:null")) {
      if (value != null && !value.isNull()) {
        fail();
      }
    } else if (Set.of("in", "not:in", "between", "not:between").contains(op)) {
      if (value == null || !value.isArray() || value.isEmpty() || value.size() > 100) {
        fail();
      }
      if (op.contains("between") && value.size() != 2) {
        fail();
      }
      value.forEach(QueryDocument::scalar);
    } else {
      scalar(value);
    }
    return new Node(op, field(n), value, List.of());
  }

  private static String field(JsonNode n) {
    JsonNode field = n.get("field");
    if (field == null
        || !field.isTextual()
        || !field.asText().matches("[A-Za-z][A-Za-z0-9_]{0,79}")) {
      fail();
    }
    return field.asText();
  }

  private static void scalar(JsonNode value) {
    if (value == null
        || !(value.isTextual() || value.isNumber() || value.isBoolean())
        || value.asText().length() > 2000) {
      fail();
    }
  }

  private static void keys(JsonNode n, Set<String> allowed) {
    if (n == null
        || !n.isObject()
        || n.properties().stream().anyMatch(e -> !allowed.contains(e.getKey()))) {
      fail();
    }
  }

  private static void fail() {
    throw new InvalidQueryException("无效或过于复杂的结构化查询");
  }
}
