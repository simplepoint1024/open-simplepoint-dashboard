package org.simplepoint.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** One interpretation of declarative OpenAPI constraints for generated forms and server writes. */
public final class SchemaConstraints {
  private SchemaConstraints() {}

  /** Length/range constraints do not implicitly make an optional field required. */
  @SuppressWarnings("deprecation")
  public static boolean required(Schema schema) {
    return schema != null
        && (schema.requiredMode() == Schema.RequiredMode.REQUIRED
            || schema.requiredMode() == Schema.RequiredMode.AUTO && schema.required());
  }

  /** Bridge existing editable text declarations, not every non-null database/audit column. */
  public static boolean required(Schema schema, Class<?> type, boolean nonNullableColumn) {
    return required(schema)
        || schema != null
            && nonNullableColumn
            && CharSequence.class.isAssignableFrom(type)
            && schema.minLength() > 0
            && schema.requiredMode() == Schema.RequiredMode.AUTO
            && !schema.nullable()
            && !schema.hidden()
            && schema.accessMode() != Schema.AccessMode.READ_ONLY;
  }

  /** Returns JSON Schema draft-7 validation keywords, not UI labels or mutable annotation state. */
  public static Map<String, Object> keywords(Schema schema, Class<?> type) {
    Map<String, Object> rules = new LinkedHashMap<>();
    if (schema == null) return rules;
    if (CharSequence.class.isAssignableFrom(type)) {
      if (schema.minLength() > 0) rules.put("minLength", schema.minLength());
      if (schema.maxLength() < Integer.MAX_VALUE) rules.put("maxLength", schema.maxLength());
      if (!schema.pattern().isEmpty()) rules.put("pattern", schema.pattern());
      if (!schema.format().isEmpty()) rules.put("format", schema.format());
    }
    if (Number.class.isAssignableFrom(type)
        || type.isPrimitive() && type != boolean.class && type != char.class) {
      if (!schema.minimum().isEmpty())
        rules.put(
            schema.exclusiveMinimum() ? "exclusiveMinimum" : "minimum",
            new BigDecimal(schema.minimum()));
      if (!schema.maximum().isEmpty())
        rules.put(
            schema.exclusiveMaximum() ? "exclusiveMaximum" : "maximum",
            new BigDecimal(schema.maximum()));
      if (schema.multipleOf() > 0) rules.put("multipleOf", BigDecimal.valueOf(schema.multipleOf()));
    }
    if (schema.allowableValues().length > 0) {
      var values =
          java.util.Arrays.stream(schema.allowableValues())
              .map(
                  value -> {
                    if (Number.class.isAssignableFrom(type)
                        || type.isPrimitive() && type != boolean.class && type != char.class)
                      return (Object) new BigDecimal(value);
                    if (type == Boolean.class || type == boolean.class)
                      return (Object) Boolean.valueOf(value);
                    return (Object) value;
                  })
              .toList();
      if (java.util.Collection.class.isAssignableFrom(type) || type.isArray())
        rules.put("items", Map.of("enum", values));
      else rules.put("enum", values);
    }
    return rules;
  }
}
