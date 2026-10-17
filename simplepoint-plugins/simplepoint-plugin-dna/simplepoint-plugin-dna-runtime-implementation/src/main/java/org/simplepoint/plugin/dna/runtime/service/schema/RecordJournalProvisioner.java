package org.simplepoint.plugin.dna.runtime.service.schema;

import org.springframework.stereotype.Component;

/** Provisions target infrastructure during authorized schema publication, never during a record write. */
@Component
public class RecordJournalProvisioner {
  private final JdbcPhysicalSchemaBackend backend;

  /** Reuses the platform's trusted schema planner and executor. */
  public RecordJournalProvisioner(final JdbcPhysicalSchemaBackend backend) {
    this.backend = backend;
  }

  /** Inspects, completes and verifies a deterministic plan, including interrupted or concurrent DDL. */
  public void prepare(final PhysicalSchemaTarget model) {
    RuntimeInfrastructureSchema.prepare(backend, RecordJournalSchema.target(model), "DNA committed record operation receipts v1",
        RecordJournalSchema.definition());
    RuntimeInfrastructureSchema.prepare(backend, RecordSnapshotSchema.target(model),
        "DNA immutable record snapshots v1", RecordSnapshotSchema.definition());
  }
}
