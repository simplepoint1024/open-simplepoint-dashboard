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
    new RecordCalculationProvisioner(backend).prepare(model);
    RuntimeInfrastructureSchema.prepare(backend, RecordPublicationSchema.target(model),
        "DNA durable record publication journal v1", RecordPublicationSchema.definition());
    RuntimeInfrastructureSchema.prepare(backend, RecordPublicationStageSchema.target(model, false),
        "DNA publication aggregate stages v1", RecordPublicationStageSchema.definition());
    RuntimeInfrastructureSchema.prepare(backend, RecordPublicationStageSchema.target(model, true),
        "DNA publication aggregate chunks v1", RecordPublicationStageSchema.chunksDefinition());
  }
}
