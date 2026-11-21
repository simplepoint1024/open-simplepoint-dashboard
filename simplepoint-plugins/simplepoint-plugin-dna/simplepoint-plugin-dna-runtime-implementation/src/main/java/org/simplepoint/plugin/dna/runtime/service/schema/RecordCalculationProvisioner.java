package org.simplepoint.plugin.dna.runtime.service.schema;

import org.springframework.stereotype.Component;

/** Prepares calculation infrastructure through authorized management, never a business write. */
@Component
public class RecordCalculationProvisioner {
  private final JdbcPhysicalSchemaBackend backend;

  /** Reuses the existing schema inspection, generated DDL and interrupted-step verification. */
  public RecordCalculationProvisioner(final JdbcPhysicalSchemaBackend backend) {
    this.backend = backend;
  }

  /** Creates or verifies the immutable definition table without changing existing definitions. */
  public void prepare(final PhysicalSchemaTarget model) {
    RuntimeInfrastructureSchema.prepare(backend, model, RuntimeInfrastructureTable.CALCULATIONS);
  }
}
