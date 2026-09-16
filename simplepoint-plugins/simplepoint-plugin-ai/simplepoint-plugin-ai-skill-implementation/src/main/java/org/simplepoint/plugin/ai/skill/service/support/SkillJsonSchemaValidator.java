package org.simplepoint.plugin.ai.skill.service.support;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Bounded validator for the declarative JSON Schema subset used by Skills.
 */
@Component
public class SkillJsonSchemaValidator {

  private static final int MAXIMUM_DEPTH = 32;

  private static final int MAXIMUM_SCHEMA_NODES = 4096;

  private static final int MAXIMUM_PROPERTIES = 1024;

  private static final int MAXIMUM_LIST_VALUES = 1024;

  private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
      "$id",
      "$schema",
      "additionalProperties",
      "const",
      "default",
      "description",
      "enum",
      "examples",
      "items",
      "maximum",
      "maxItems",
      "maxLength",
      "minimum",
      "minItems",
      "minLength",
      "properties",
      "required",
      "title",
      "type"
  );

  private static final Set<String> SUPPORTED_TYPES = Set.of(
      "array",
      "boolean",
      "integer",
      "null",
      "number",
      "object",
      "string"
  );

  /**
   * Ensures a schema only uses the deterministic subset implemented here.
   */
  public void validateSchema(
      final Map<String, Object> schema,
      final String label
  ) {
    if (schema == null) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    validateSchemaNode(schema, label, 0, new SchemaBudget());
  }

  /**
   * Returns whether a schema can be enforced by the deterministic validator.
   * This is used for optional third-party MCP output schemas whose dialect may
   * contain keywords outside the platform's bounded subset.
   */
  public boolean supports(final Map<String, Object> schema) {
    try {
      validateSchema(schema, "JSON Schema");
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  /**
   * Validates a value against the supported schema subset.
   */
  public void validate(
      final Map<String, Object> schema,
      final Object value,
      final String label
  ) {
    validateSchema(schema, label + " Schema");
    validateNode(schema, value, label, 0);
  }

  private void validateSchemaNode(
      final Map<String, Object> schema,
      final String path,
      final int depth,
      final SchemaBudget budget
  ) {
    assertDepth(depth, path);
    budget.consume(path);
    Set<String> unknown = new HashSet<>(schema.keySet());
    unknown.removeAll(SUPPORTED_KEYWORDS);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          path + " contains unsupported JSON Schema keyword "
              + unknown.iterator().next()
      );
    }
    if (schema.get("type") != null) {
      if (!(schema.get("type") instanceof String type)
          || !SUPPORTED_TYPES.contains(type)) {
        throw new IllegalArgumentException(
            path + " contains an unsupported type"
        );
      }
    }
    validateStringList(schema.get("required"), path + ".required", true);
    validateList(schema.get("enum"), path + ".enum");
    validateList(schema.get("examples"), path + ".examples");
    validateNonNegativeInteger(schema, "minItems", path);
    validateNonNegativeInteger(schema, "maxItems", path);
    validateNonNegativeInteger(schema, "minLength", path);
    validateNonNegativeInteger(schema, "maxLength", path);
    validateNumericKeyword(schema, "minimum", path);
    validateNumericKeyword(schema, "maximum", path);
    assertOrderedBounds(schema, "minItems", "maxItems", path);
    assertOrderedBounds(schema, "minLength", "maxLength", path);
    assertOrderedBounds(schema, "minimum", "maximum", path);

    Object additionalProperties = schema.get("additionalProperties");
    if (additionalProperties != null
        && !(additionalProperties instanceof Boolean)) {
      throw new IllegalArgumentException(
          path + ".additionalProperties must be boolean"
      );
    }
    Object properties = schema.get("properties");
    if (properties != null && !(properties instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(path + ".properties must be an object");
    }
    if (properties instanceof Map<?, ?> map) {
      if (map.size() > MAXIMUM_PROPERTIES) {
        throw new IllegalArgumentException(
            path + ".properties exceeds maximum property count"
        );
      }
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key) || key.isBlank()
            || !(entry.getValue() instanceof Map<?, ?> nested)) {
          throw new IllegalArgumentException(
              path + ".properties must contain object schemas"
          );
        }
        validateSchemaNode(
            asStringMap(nested),
            path + ".properties." + key,
            depth + 1,
            budget
        );
      }
    }
    Object items = schema.get("items");
    if (items != null && !(items instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(path + ".items must be an object");
    }
    if (items instanceof Map<?, ?> nested) {
      validateSchemaNode(
          asStringMap(nested),
          path + ".items",
          depth + 1,
          budget
      );
    }
  }

  private void validateNode(
      final Map<String, Object> schema,
      final Object value,
      final String path,
      final int depth
  ) {
    if (depth > MAXIMUM_DEPTH) {
      throw new IllegalArgumentException(path + " exceeds maximum JSON depth");
    }
    if (schema.containsKey("const")
        && !Objects.equals(schema.get("const"), value)) {
      throw new IllegalArgumentException(path + " does not match const");
    }
    if (schema.get("enum") instanceof List<?> values
        && !values.contains(value)) {
      throw new IllegalArgumentException(path + " is not an allowed value");
    }
    String type = schema.get("type") instanceof String candidate
        ? candidate : null;
    if (type != null && !matchesType(type, value)) {
      throw new IllegalArgumentException(path + " must be " + type);
    }
    if (value instanceof Map<?, ?> object) {
      validateObject(schema, object, path, depth);
    } else if (value instanceof List<?> array) {
      validateArray(schema, array, path, depth);
    } else if (value instanceof String text) {
      validateString(schema, text, path);
    } else if (value instanceof Number number) {
      validateNumber(schema, number, path);
    }
  }

  private void validateObject(
      final Map<String, Object> schema,
      final Map<?, ?> value,
      final String path,
      final int depth
  ) {
    if (schema.get("required") instanceof List<?> required) {
      for (Object name : required) {
        if (!(name instanceof String key) || !value.containsKey(key)) {
          throw new IllegalArgumentException(
              path + " is missing required property " + name
          );
        }
      }
    }
    Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> map
        ? map : Map.of();
    for (Map.Entry<?, ?> entry : value.entrySet()) {
      String key = String.valueOf(entry.getKey());
      Object propertySchema = properties.get(key);
      if (propertySchema instanceof Map<?, ?> nested) {
        validateNode(asStringMap(nested), entry.getValue(),
            path + "." + key, depth + 1);
      } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
        throw new IllegalArgumentException(
            path + " contains unsupported property " + key
        );
      }
    }
  }

  private void validateArray(
      final Map<String, Object> schema,
      final List<?> value,
      final String path,
      final int depth
  ) {
    assertBound(schema, "minItems", value.size(), true, path);
    assertBound(schema, "maxItems", value.size(), false, path);
    if (schema.get("items") instanceof Map<?, ?> itemSchema) {
      Map<String, Object> nested = asStringMap(itemSchema);
      for (int index = 0; index < value.size(); index++) {
        validateNode(nested, value.get(index),
            path + "[" + index + "]", depth + 1);
      }
    }
  }

  private void validateString(
      final Map<String, Object> schema,
      final String value,
      final String path
  ) {
    assertBound(schema, "minLength", value.length(), true, path);
    assertBound(schema, "maxLength", value.length(), false, path);
  }

  private void validateNumber(
      final Map<String, Object> schema,
      final Number value,
      final String path
  ) {
    BigDecimal actual = new BigDecimal(value.toString());
    if (schema.get("minimum") instanceof Number minimum
        && actual.compareTo(new BigDecimal(minimum.toString())) < 0) {
      throw new IllegalArgumentException(path + " is below minimum");
    }
    if (schema.get("maximum") instanceof Number maximum
        && actual.compareTo(new BigDecimal(maximum.toString())) > 0) {
      throw new IllegalArgumentException(path + " exceeds maximum");
    }
  }

  private static boolean matchesType(final String type, final Object value) {
    return switch (type) {
      case "object" -> value instanceof Map<?, ?>;
      case "array" -> value instanceof List<?>;
      case "string" -> value instanceof String;
      case "number" -> value instanceof Number;
      case "integer" -> value instanceof Byte
          || value instanceof Short
          || value instanceof Integer
          || value instanceof Long
          || value instanceof java.math.BigInteger;
      case "boolean" -> value instanceof Boolean;
      case "null" -> value == null;
      default -> throw new IllegalArgumentException(
          "Unsupported Skill JSON Schema type: " + type
      );
    };
  }

  private static void assertBound(
      final Map<String, Object> schema,
      final String keyword,
      final int actual,
      final boolean minimum,
      final String path
  ) {
    if (schema.get(keyword) instanceof Number bound) {
      int expected = bound.intValue();
      if (minimum && actual < expected
          || !minimum && actual > expected) {
        throw new IllegalArgumentException(
            path + " violates " + keyword
        );
      }
    }
  }

  private static Map<String, Object> asStringMap(final Map<?, ?> value) {
    java.util.LinkedHashMap<String, Object> result =
        new java.util.LinkedHashMap<>();
    value.forEach((key, nested) -> result.put(String.valueOf(key), nested));
    return result;
  }

  private static void validateStringList(
      final Object value,
      final String path,
      final boolean unique
  ) {
    if (value == null) {
      return;
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(path + " must be an array");
    }
    assertListSize(list, path);
    Set<String> values = new HashSet<>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()
          || unique && !values.add(text)) {
        throw new IllegalArgumentException(
            path + " must contain unique non-blank strings"
        );
      }
    }
  }

  private static void validateList(final Object value, final String path) {
    if (value != null && !(value instanceof List<?>)) {
      throw new IllegalArgumentException(path + " must be an array");
    }
    if (value instanceof List<?> list) {
      assertListSize(list, path);
    }
  }

  private static void assertListSize(
      final List<?> list,
      final String path
  ) {
    if (list.size() > MAXIMUM_LIST_VALUES) {
      throw new IllegalArgumentException(path + " exceeds maximum item count");
    }
  }

  private static void validateNonNegativeInteger(
      final Map<String, Object> schema,
      final String keyword,
      final String path
  ) {
    Object value = schema.get(keyword);
    if (value != null && (!(value instanceof Number number)
        || number.longValue() < 0
        || new BigDecimal(number.toString()).stripTrailingZeros().scale() > 0)) {
      throw new IllegalArgumentException(
          path + "." + keyword + " must be a non-negative integer"
      );
    }
  }

  private static void validateNumericKeyword(
      final Map<String, Object> schema,
      final String keyword,
      final String path
  ) {
    if (schema.get(keyword) != null
        && !(schema.get(keyword) instanceof Number)) {
      throw new IllegalArgumentException(
          path + "." + keyword + " must be a number"
      );
    }
  }

  private static void assertOrderedBounds(
      final Map<String, Object> schema,
      final String minimum,
      final String maximum,
      final String path
  ) {
    if (schema.get(minimum) instanceof Number lower
        && schema.get(maximum) instanceof Number upper
        && new BigDecimal(lower.toString())
        .compareTo(new BigDecimal(upper.toString())) > 0) {
      throw new IllegalArgumentException(
          path + "." + minimum + " must not exceed " + maximum
      );
    }
  }

  private static void assertDepth(final int depth, final String path) {
    if (depth > MAXIMUM_DEPTH) {
      throw new IllegalArgumentException(path + " exceeds maximum JSON depth");
    }
  }

  private static final class SchemaBudget {

    private int nodes;

    private void consume(final String path) {
      nodes++;
      if (nodes > MAXIMUM_SCHEMA_NODES) {
        throw new IllegalArgumentException(
            path + " exceeds maximum JSON Schema complexity"
        );
      }
    }
  }
}
