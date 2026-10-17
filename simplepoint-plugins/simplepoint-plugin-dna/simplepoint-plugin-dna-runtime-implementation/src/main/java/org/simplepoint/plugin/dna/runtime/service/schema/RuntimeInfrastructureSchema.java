package org.simplepoint.plugin.dna.runtime.service.schema;

import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Shared reconciliation for platform-owned target tables, including concurrent/interrupted DDL. */
final class RuntimeInfrastructureSchema {

  private RuntimeInfrastructureSchema() {
  }

  static void prepare(final JdbcPhysicalSchemaBackend backend, final PhysicalSchemaTarget target,
      final String description, final PhysicalSchemaDefinition definition) {
    InitialSchemaPlan plan = backend.planInitial(target, description, definition);
    for (PlannedSchemaStep step : plan.steps()) {
      if (backend.isSatisfied(step, plan.targetSnapshot(), backend.inspect(target, plan.dialectId()))) {
        continue;
      }
      try {
        backend.execute(target, plan.dialectId(), step);
      } catch (DnaRuntimeException failure) {
        if (!backend.isSatisfied(step, plan.targetSnapshot(), backend.inspect(target, plan.dialectId()))) {
          throw failure;
        }
      }
    }
    if (!backend.inspect(target, plan.dialectId()).checksum().equals(plan.targetSnapshot().checksum())) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_VERIFICATION_FAILED,
          "Target infrastructure does not match its versioned schema");
    }
  }
}
