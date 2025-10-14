package org.simplepoint.data.json.schema.module;

import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.core.annotation.Order;

/**
 * A custom module to enhance JSON Schema generation with OpenAPI annotations.
 * This module extracts title and description from the @Schema annotation
 * and incorporates them into the generated JSON Schema.
 */
public class OpenApiModule implements Module {
  /**
   * Applies the OpenAPI annotation processing to the schema generator configuration.
   *
   * @param builder the schema generator configuration builder
   */
  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder.forFields()
        .withInstanceAttributeOverride((attributes, field, context) -> {
          Order order = field.getAnnotation(Order.class);
          if (order != null) {
            attributes.put("x-order", order.value());
          }
        })
        .withTitleResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null ? schema.title() : null;
        }).withDescriptionResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null ? schema.description() : null;
        }).withStringMaxLengthResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.maxLength() != Integer.MAX_VALUE ? schema.maxLength() : null;
        }).withStringMinLengthResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.minLength() != 0 ? schema.minLength() : null;
        }).withReadOnlyCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.accessMode() == Schema.AccessMode.READ_ONLY;
        }).withWriteOnlyCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.accessMode() == Schema.AccessMode.WRITE_ONLY;
        }).withNullableCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.nullable();
        }).withDefaultResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && !schema.defaultValue().isEmpty() ? schema.defaultValue() : null;
        }).withIgnoreCheck(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && schema.hidden();
        }).withStringFormatResolver(field -> {
          Schema schema = field.getAnnotation(Schema.class);
          return schema != null && !schema.format().isEmpty() ? schema.format() : null;
        });
  }
}
