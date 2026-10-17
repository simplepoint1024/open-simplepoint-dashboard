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

  /** Reconciles the namespace table; model rows are initialized separately in a physical transaction. */
  public void prepare(final PhysicalSchemaTarget model) {
    RuntimeInfrastructureSchema.prepare(backend, RecordWriteFenceSchema.target(model),
        "DNA transactional record write admission v1", RecordWriteFenceSchema.definition());
  }
}
