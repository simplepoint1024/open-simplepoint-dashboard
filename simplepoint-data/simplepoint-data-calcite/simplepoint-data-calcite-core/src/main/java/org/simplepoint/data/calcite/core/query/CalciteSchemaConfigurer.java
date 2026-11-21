package org.simplepoint.data.calcite.core.query;

import java.time.ZoneId;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;

/** Callback for registering Calcite schemas, tables, and functions for one execution session. */
@FunctionalInterface
public interface CalciteSchemaConfigurer {

  /**
   * Configures the root schema for the current Calcite session.
   *
   * @param rootSchema session root schema
   */
  void configure(SchemaPlus rootSchema);

  /**
   * Optional trusted type-system class for this session; SQL requests cannot select executable
   * classes.
   */
  default Class<? extends RelDataTypeSystem> typeSystem() {
    return null;
  }

  /** Optional trusted session zone; null retains Calcite's default for existing callers. */
  default ZoneId timeZone() {
    return null;
  }

  /**
   * Optional trusted execution-owner check of actual prepared JDBC write bindings. An owner that
   * requires admission must supply it; absence is not proof of authorization or ownership. The
   * owner retains its physical locks/transaction through execution and completion.
   */
  default CalciteWriteAdmission writeAdmission() {
    return null;
  }
}
