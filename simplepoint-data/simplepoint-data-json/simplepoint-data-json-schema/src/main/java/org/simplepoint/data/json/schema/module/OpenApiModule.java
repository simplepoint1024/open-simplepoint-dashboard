package org.simplepoint.data.json.schema.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.Instant;
import org.simplepoint.api.schema.DictionaryField;
import org.simplepoint.api.schema.SchemaConstraints;
import org.simplepoint.api.schema.UploadField;
import org.springframework.core.annotation.Order;

/**
 * A custom module to enhance JSON Schema generation with OpenAPI annotations. This module extracts
 * title and description from the @Schema annotation and incorporates them into the generated JSON
 * Schema.
 */
public class OpenApiModule implements Module {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withTypeAttributeOverride(
            (attributes, type, context) -> {
              if (type.getType().getErasedType() != BigDecimal.class) return;
              boolean nullable =
                  attributes.path("type").isArray()
                      && java.util.stream.StreamSupport.stream(
                              attributes.path("type").spliterator(), false)
                          .anyMatch(item -> "null".equals(item.asText()));
              if (nullable) attributes.putArray("type").add("string").add("null");
              else attributes.put("type", "string");
              attributes.put(
                  "pattern", "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$");
            });
    builder
        .forFields()
        .withInstanceAttributeOverride(
            (attributes, field, context) -> {
              // x-order from Spring's @Order
              Order order = field.getAnnotation(Order.class);
              if (order != null) {
                attributes.put("x-order", order.value());
              }

              // Vendor extensions from @Schema(extensions = ...)
              Schema schema = field.getAnnotationConsideringFieldAndGetterIfSupported(Schema.class);
              if (schema != null) {
                applyExtensions(attributes, schema);
                SchemaConstraints.keywords(schema, field.getType().getErasedType())
                    .forEach(
                        (keyword, value) -> {
                          // Decimal controls use exact strings and the existing x-decimal
                          // constraint contract.
                          if (field.getType().getErasedType() != BigDecimal.class
                              && !keyword.equals("enum")
                              && !keyword.equals("items")
                              && !(keyword.equals("format")
                                  && field.getAnnotation(UploadField.class) != null))
                            attributes.set(keyword, MAPPER.valueToTree(value));
                        });
              }
              if (field.getType().getErasedType() == BigDecimal.class) {
                applyDecimalField(attributes, field.getAnnotation(Column.class), schema);
              }

              DictionaryField dictionaryField = field.getAnnotation(DictionaryField.class);
              if (dictionaryField != null && !dictionaryField.value().isBlank()) {
                applyDictionaryField(attributes, dictionaryField.value());
              }

              UploadField uploadField = field.getAnnotation(UploadField.class);
              if (uploadField != null) {
                applyUploadField(attributes, uploadField);
              }
              BeanValidationMetadata.apply(attributes, field);
            })
        .withRequiredCheck(
            field -> {
              var column = field.getAnnotation(Column.class);
              return SchemaConstraints.required(
                  field.getAnnotation(Schema.class),
                  field.getType().getErasedType(),
                  column != null && !column.nullable());
            })
        .withEnumResolver(
            field -> {
              var rules =
                  SchemaConstraints.keywords(
                      field.getAnnotation(Schema.class), field.getType().getErasedType());
              if (!(rules.get("enum") instanceof java.util.List<?> values)) return null;
              var column = field.getAnnotation(Column.class);
              if (field.getType().getErasedType() == String.class
                  && !BeanValidationMetadata.nonNullable(field)
                  && !SchemaConstraints.required(
                      field.getAnnotation(Schema.class),
                      String.class,
                      column != null && !column.nullable())) {
                var nullable = new java.util.ArrayList<Object>(values);
                nullable.add(null);
                return nullable;
              }
              return values;
            })
        .withTitleResolver(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null ? schema.title() : null;
            })
        .withDescriptionResolver(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null ? schema.description() : null;
            })
        .withStringMaxLengthResolver(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null && schema.maxLength() != Integer.MAX_VALUE
                  ? schema.maxLength()
                  : null;
            })
        .withStringMinLengthResolver(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null && schema.minLength() != 0 ? schema.minLength() : null;
            })
        .withReadOnlyCheck(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null && schema.accessMode() == Schema.AccessMode.READ_ONLY;
            })
        .withWriteOnlyCheck(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null && schema.accessMode() == Schema.AccessMode.WRITE_ONLY;
            })
        .withNullableCheck(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              var column = field.getAnnotation(Column.class);
              if (SchemaConstraints.required(
                      schema, field.getType().getErasedType(), column != null && !column.nullable())
                  || BeanValidationMetadata.nonNullable(field)) return false;
              // 1) Respect @Schema(nullable = true)
              if (schema != null && schema.nullable()) {
                return true;
              }
              // 2) Make String and Instant nullable by default
              Class<?> raw = field.getType().getErasedType();
              return raw == String.class
                  || raw == Instant.class
                  || raw == BigDecimal.class
                      && (field.getAnnotation(Column.class) == null
                          || field.getAnnotation(Column.class).nullable());
            })
        .withDefaultResolver(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              if (schema == null || schema.defaultValue().isEmpty()) return null;
              Class<?> type = field.getType().getErasedType();
              if (type == String.class || type == BigDecimal.class || type.isEnum())
                return schema.defaultValue();
              try {
                return MAPPER.readTree(schema.defaultValue());
              } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalArgumentException(
                    "Non-string schema defaults must be valid JSON", error);
              }
            })
        .withIgnoreCheck(
            field -> {
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null && schema.hidden();
            })
        .withStringFormatResolver(
            field -> {
              if (field.getAnnotation(UploadField.class) != null) {
                return null;
              }
              Schema schema = field.getAnnotation(Schema.class);
              return schema != null && !schema.format().isEmpty() ? schema.format() : null;
            });
  }

  private static void applyDecimalField(ObjectNode attributes, Column column, Schema schema) {
    ObjectNode decimal = attributes.putObject("x-decimal");
    if (column != null && column.precision() > 0) {
      int precision = column.precision(), scale = column.scale();
      decimal.put("precision", precision);
      decimal.put("scale", scale);
      BigDecimal limit =
          BigDecimal.ONE
              .scaleByPowerOfTen(precision - scale)
              .subtract(BigDecimal.ONE.movePointLeft(scale));
      decimal.put("minimum", limit.negate().toPlainString());
      decimal.put("maximum", limit.toPlainString());
    }
    if (schema != null) {
      if (!schema.minimum().isEmpty()) decimal.put("minimum", schema.minimum());
      if (!schema.maximum().isEmpty()) decimal.put("maximum", schema.maximum());
      if (schema.exclusiveMinimum()) decimal.put("exclusiveMinimum", true);
      if (schema.exclusiveMaximum()) decimal.put("exclusiveMaximum", true);
    }
    ObjectNode ui =
        attributes.get("x-ui") instanceof ObjectNode object ? object : attributes.putObject("x-ui");
    if (!ui.hasNonNull("widget")) ui.put("widget", "Decimal");
  }

  private static void applyExtensions(ObjectNode attributes, Schema schema) {
    Extension[] extensions = schema.extensions();
    if (extensions == null) {
      return;
    }
    for (Extension ext : extensions) {
      String extName = ext.name();
      ExtensionProperty[] props = ext.properties();

      // Named group like "x-ui": { ... }
      if (extName != null && !extName.isEmpty()) {
        ObjectNode node = attributes.objectNode();
        putProperties(node, props);
        attributes.set(extName, node);
        continue;
      }

      // No group name: flatten properties, only those starting with "x-"
      putProperties(attributes, props);
    }
  }

  private static void applyDictionaryField(ObjectNode attributes, String dictionaryCode) {
    String normalizedCode = dictionaryCode.trim();
    attributes.put("x-dictionary-code", normalizedCode);
    JsonNode currentUi = attributes.get("x-ui");
    ObjectNode ui =
        currentUi instanceof ObjectNode objectNode ? objectNode : attributes.putObject("x-ui");
    ui.put("dictCode", normalizedCode);
    if (!ui.hasNonNull("widget")) {
      ui.put("widget", "select");
    }
  }

  private static void applyUploadField(ObjectNode attributes, UploadField uploadField) {
    String uploadType = uploadField.type().name().toLowerCase(java.util.Locale.ROOT);
    ObjectNode upload = attributes.putObject("x-upload");
    upload.put("type", uploadType);

    ObjectNode ui =
        attributes.get("x-ui") instanceof ObjectNode objectNode
            ? objectNode
            : attributes.putObject("x-ui");
    ui.put("widget", uploadField.type() == UploadField.Type.IMAGE ? "OssImage" : "OssFile");
    ObjectNode options =
        ui.get("options") instanceof ObjectNode objectNode ? objectNode : ui.putObject("options");

    putUploadOption(upload, options, "directory", uploadField.directory());
    putUploadOption(upload, options, "sourceServiceName", uploadField.sourceServiceName());
    putUploadOption(upload, options, "accept", uploadField.accept());
    putUploadOption(upload, options, "shape", uploadField.shape());
    if (uploadField.maxSizeMb() > 0) {
      upload.put("maxSizeMb", uploadField.maxSizeMb());
      options.put("maxSizeMb", uploadField.maxSizeMb());
    }
  }

  private static void putUploadOption(
      ObjectNode upload, ObjectNode options, String name, String value) {
    if (value != null && !value.isBlank()) {
      String normalized = value.trim();
      upload.put(name, normalized);
      options.put(name, normalized);
    }
  }

  private static void putProperties(ObjectNode target, ExtensionProperty[] props) {
    if (props == null) {
      return;
    }
    for (ExtensionProperty p : props) {
      String name = p.name();
      if (name == null || name.isEmpty()) {
        continue;
      }
      String value = p.value();
      if (p.parseValue()) {
        try {
          JsonNode parsed = MAPPER.readTree(value);
          target.set(name, parsed);
        } catch (Exception e) {
          // Fallback to raw string if parsing fails
          target.put(name, value);
        }
      } else {
        target.put(name, value);
      }
    }
  }
}
