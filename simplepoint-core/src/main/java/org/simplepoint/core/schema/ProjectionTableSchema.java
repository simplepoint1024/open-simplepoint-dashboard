package org.simplepoint.core.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.core.query.QueryCapabilities;

/** Query-only table schema for explicit scalar DTOs; CRUD forms keep their annotated provider. */
public final class ProjectionTableSchema {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ProjectionTableSchema() {}

  /** Stable filter value and its translated display label. */
  public record Choice(String value, String title) {}

  /** Builds a query-only schema intersected with the current field-read policy. */
  public static Map<String, Object> schema(
      Class<?> type, String namespace, Map<String, List<Choice>> choices) {
    var schema = JSON.createObjectNode().put("type", "object");
    var properties = schema.putObject("properties");
    int order = 0;
    for (var definition : QueryCapabilities.declaration(type).value()) {
      String name = definition.value();
      Class<?> scalar = QueryCapabilities.scalarType(type, name);
      var column =
          properties
              .putObject(name)
              .put("type", jsonType(scalar))
              .put("title", "i18n:" + namespace + "." + name)
              .put("x-order", order++);
      column.putObject("x-ui").put("x-list-visible", true);
      if (scalar == Instant.class || scalar == LocalDateTime.class) {
        column.put("format", "date-time");
      }
      if (scalar == LocalDate.class) {
        column.put("format", "date");
      }
      if (choices.containsKey(name)) {
        var values = column.putArray("oneOf");
        choices
            .get(name)
            .forEach(
                value ->
                    values.addObject().put("const", value.value()).put("title", value.title()));
      }
    }
    FieldPermissionPolicy.applySchema(type, schema);
    QueryCapabilities.applySchema(type, schema);
    return Map.of(
        "schema",
        JSON.convertValue(schema, new TypeReference<Map<String, Object>>() {}),
        "buttons",
        List.of());
  }

  private static String jsonType(Class<?> scalar) {
    if (scalar == boolean.class || scalar == Boolean.class) {
      return "boolean";
    }
    if (scalar == int.class
        || scalar == Integer.class
        || scalar == long.class
        || scalar == Long.class
        || scalar == short.class
        || scalar == Short.class
        || scalar == byte.class
        || scalar == Byte.class
        || scalar == BigInteger.class) {
      return "integer";
    }
    if (scalar == double.class
        || scalar == Double.class
        || scalar == float.class
        || scalar == Float.class
        || scalar == BigDecimal.class) {
      return "number";
    }
    return "string";
  }
}
