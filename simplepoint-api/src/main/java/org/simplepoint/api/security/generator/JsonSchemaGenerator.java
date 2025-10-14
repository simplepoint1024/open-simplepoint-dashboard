package org.simplepoint.api.security.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.simplepoint.api.base.BaseDetailsService;

/**
 * JSON Schema generator.
 */
public interface JsonSchemaGenerator extends BaseDetailsService {
  /**
   * Generate schema by domain class.
   *
   * @param domainClass domain class.
   * @return schema.
   */
  ObjectNode generateSchema(Class<?> domainClass);
}
