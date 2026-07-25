package org.simplepoint.data.json.schema.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.simplepoint.api.schema.DictionaryField;
import org.simplepoint.api.schema.UploadField;
import org.springframework.core.annotation.Order;

/**
 * A custom module to enhance JSON Schema generation with OpenAPI annotations.
 * This module extracts title and description from the @Schema annotation
 * and incorporates them into the generated JSON Schema.
 */
public class OpenApiModule implements Module {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder.forFields()
        .withInstanceAttributeOverride((attributes, field, context) -> {
          // x-order from Spring's @Order
          Order order = field.getAnnotation(Order.class);
          if (order != null) {
            attributes.put("x-order", order.value());
          }

          // Vendor extensions from @Schema(extensions = ...)
          Schema schema = field.getAnnotation(Schema.class);
          if (schema != null) {
            applyExtensions(attributes, schema);
          }

          DictionaryField dictionaryField = field.getAnnotation(DictionaryField.class);
          if (dictionaryField != null && !dictionaryField.value().isBlank()) {
            applyDictionaryField(attributes, dictionaryField.value());
          }

          UploadField uploadField = field.getAnnotation(UploadField.class);
          if (uploadField != null) {
            applyUploadField(attributes, uploadField);
          }
        })
        .withTitleResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null ? schema.title() : null;
        })
        .withDescriptionResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null ? schema.description() : null;
        })
        .withStringMaxLengthResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.maxLength() != Integer.MAX_VALUE ? schema.maxLength() : null;
        })
        .withStringMinLengthResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.minLength() != 0 ? schema.minLength() : null;
        })
        .withReadOnlyCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.accessMode() == Schema.AccessMode.READ_ONLY;
        })
        .withWriteOnlyCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.accessMode() == Schema.AccessMode.WRITE_ONLY;
        })
        .withNullableCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          // 1) Respect @Schema(nullable = true)
          if (schema != null && schema.nullable()) {
            return true;
          }
          // 2) Make String and Instant nullable by default
          Class<?> raw = field.getType().getErasedType();
          return raw == String.class || raw == Instant.class;
        })
        .withDefaultResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && !schema.defaultValue().isEmpty() ? schema.defaultValue() : null;
        })
        .withIgnoreCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.hidden();
        })
        .withStringFormatResolver(field -> {
          if (field.getAnnotation(UploadField.class) != null) {
            return null;
          }
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && !schema.format().isEmpty() ? schema.format() : null;
        });
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
    ObjectNode ui = currentUi instanceof ObjectNode objectNode
        ? objectNode
        : attributes.putObject("x-ui");
    ui.put("dictCode", normalizedCode);
    if (!ui.hasNonNull("widget")) {
      ui.put("widget", "select");
    }
  }

  private static void applyUploadField(ObjectNode attributes, UploadField uploadField) {
    String uploadType = uploadField.type().name().toLowerCase(java.util.Locale.ROOT);
    ObjectNode upload = attributes.putObject("x-upload");
    upload.put("type", uploadType);

    ObjectNode ui = attributes.get("x-ui") instanceof ObjectNode objectNode
        ? objectNode
        : attributes.putObject("x-ui");
    ui.put("widget", uploadField.type() == UploadField.Type.IMAGE ? "OssImage" : "OssFile");
    ObjectNode options = ui.get("options") instanceof ObjectNode objectNode
        ? objectNode
        : ui.putObject("options");

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
      ObjectNode upload,
      ObjectNode options,
      String name,
      String value
  ) {
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
