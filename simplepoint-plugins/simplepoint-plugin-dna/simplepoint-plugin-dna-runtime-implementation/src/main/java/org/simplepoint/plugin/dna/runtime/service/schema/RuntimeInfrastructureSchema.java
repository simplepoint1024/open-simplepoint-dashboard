package org.simplepoint.plugin.dna.runtime.service.schema;

import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Shared reconciliation for platform-owned target tables, including concurrent/interrupted DDL. */
final class RuntimeInfrastructureSchema {

  private RuntimeInfrastructureSchema() {}

  static void prepare(
      final JdbcPhysicalSchemaBackend backend,
      final PhysicalSchemaTarget model,
      final RuntimeInfrastructureTable table) {
    backend.withExclusiveInfrastructure(
        model,
        table,
        session -> {
          reconcile(backend, session, table);
          return null;
        });
  }

  static void reconcile(
      JdbcPhysicalSchemaBackend backend,
      JdbcPhysicalSchemaBackend.PublicationSession session,
      RuntimeInfrastructureTable table) {
    InitialSchemaPlan plan = session.plan(table.description(), null, table.definition());
    for (PlannedSchemaStep step : plan.steps()) {
      if (backend.isSatisfied(step, plan.targetSnapshot(), session.inspect(plan.dialectId()))) {
        continue;
      }
      try {
        session.execute(plan.dialectId(), step);
      } catch (DnaRuntimeException failure) {
        if (!backend.isSatisfied(step, plan.targetSnapshot(), session.inspect(plan.dialectId()))) {
          throw failure;
        }
      }
    }
    if (!session.inspect(plan.dialectId()).checksum().equals(plan.targetSnapshot().checksum())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_VERIFICATION_FAILED,
          "Target infrastructure does not match its versioned schema");
    }
  }
}
