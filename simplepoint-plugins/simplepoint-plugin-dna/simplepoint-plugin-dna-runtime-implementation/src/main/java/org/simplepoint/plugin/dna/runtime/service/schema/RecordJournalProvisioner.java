package org.simplepoint.plugin.dna.runtime.service.schema;

import org.springframework.stereotype.Component;

/**
 * Provisions target infrastructure during authorized schema publication, never during a record
 * write.
 */
@Component
public class RecordJournalProvisioner {
  private final JdbcPhysicalSchemaBackend backend;

  /** Reuses the platform's trusted schema planner and executor. */
  public RecordJournalProvisioner(final JdbcPhysicalSchemaBackend backend) {
    this.backend = backend;
  }

  /**
   * Inspects, completes and verifies a deterministic plan, including interrupted or concurrent DDL.
   */
  public void prepare(final PhysicalSchemaTarget model) {
    RuntimeInfrastructureSchema.prepare(backend, model, RuntimeInfrastructureTable.OPERATIONS);
    RuntimeInfrastructureSchema.prepare(backend, model, RuntimeInfrastructureTable.SNAPSHOTS);
    RuntimeInfrastructureSchema.prepare(
        backend, model, RuntimeInfrastructureTable.REFERENCE_COPIES);
  }
}
