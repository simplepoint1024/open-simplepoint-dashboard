package org.simplepoint.data.calcite.core.query;

import org.apache.calcite.schema.SchemaPlus;

/**
 * Callback for registering Calcite schemas, tables, and functions for one execution session.
 */
@FunctionalInterface
public interface CalciteSchemaConfigurer {

  /**
   * Configures the root schema for the current Calcite session.
   *
   * @param rootSchema session root schema
   */
  void configure(SchemaPlus rootSchema);
}
