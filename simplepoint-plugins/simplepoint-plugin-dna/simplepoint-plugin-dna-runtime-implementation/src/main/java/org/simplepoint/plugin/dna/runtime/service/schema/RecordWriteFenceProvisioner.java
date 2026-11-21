package org.simplepoint.plugin.dna.runtime.service.schema;

import org.springframework.stereotype.Component;

/** Prepares write-admission infrastructure only from an authorized management operation. */
@Component
public class RecordWriteFenceProvisioner {

  private final JdbcPhysicalSchemaBackend backend;

  /** Uses the same inspected schema plan and verification as the operation journal. */
  public RecordWriteFenceProvisioner(final JdbcPhysicalSchemaBackend backend) {
    this.backend = backend;
  }

  /**
   * Reconciles the namespace table; model rows are initialized separately in a physical
   * transaction.
   */
  public void prepare(final PhysicalSchemaTarget model) {
    RuntimeInfrastructureSchema.prepare(backend, model, RuntimeInfrastructureTable.WRITE_FENCES);
    new RecordCalculationProvisioner(backend).prepare(model);
    RuntimeInfrastructureSchema.prepare(backend, model, RuntimeInfrastructureTable.PUBLICATIONS);
    RuntimeInfrastructureSchema.prepare(
        backend, model, RuntimeInfrastructureTable.PUBLICATION_STAGES);
    RuntimeInfrastructureSchema.prepare(
        backend, model, RuntimeInfrastructureTable.PUBLICATION_CHUNKS);
  }
}
