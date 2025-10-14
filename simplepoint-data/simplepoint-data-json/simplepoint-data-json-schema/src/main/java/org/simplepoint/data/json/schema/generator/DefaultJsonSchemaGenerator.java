package org.simplepoint.data.json.schema.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;

/**
 * Default JSON Schema generator.
 */
public class DefaultJsonSchemaGenerator implements JsonSchemaGenerator {
  private final SchemaGenerator schemaGenerator;

  /**
   * Constructor for DefaultJsonSchemaGenerator.
   *
   * @param schemaGenerator the SchemaGenerator to use for generating schemas
   */
  public DefaultJsonSchemaGenerator(SchemaGenerator schemaGenerator) {
    this.schemaGenerator = schemaGenerator;
  }

  /**
   * Generate schema by domain class.
   *
   * @param domainClass domain class.
   * @return schema.
   */
  @Override
  public ObjectNode generateSchema(Class<?> domainClass) {
    return schemaGenerator.generateSchema(domainClass);
  }
}
