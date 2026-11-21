package org.simplepoint.plugin.dna.runtime.service.schema;

import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** The complete, versioned set of shared tables owned by the DNA record runtime. */
public enum RuntimeInfrastructureTable {
  OPERATIONS(RecordJournalSchema.TABLE, "DNA committed record operation receipts v1"),
  SNAPSHOTS(RecordSnapshotSchema.TABLE, "DNA immutable record snapshots v1"),
  REFERENCE_COPIES(RecordReferenceCopySchema.TABLE, "DNA protected reference copy origins v1"),
  WRITE_FENCES(RecordWriteFenceSchema.TABLE, "DNA transactional record write admission v1"),
  CALCULATIONS(RecordCalculationSchema.TABLE, "DNA immutable record calculation definitions v1"),
  PUBLICATIONS(RecordPublicationSchema.TABLE, "DNA durable record publication journal v1"),
  PUBLICATION_STAGES(RecordPublicationStageSchema.TABLE, "DNA publication aggregate stages v1"),
  PUBLICATION_CHUNKS(RecordPublicationStageSchema.CHUNKS, "DNA publication aggregate chunks v1");

  private final String table;
  private final String description;

  RuntimeInfrastructureTable(String table, String description) {
    this.table = table;
    this.description = description;
  }

  /** The stable namespace-local table identity. */
  public String table() {
    return table;
  }

  /** Fence rows are accessed by their JDBC lock adapter; other ledgers use borrowed Calcite SQL. */
  public boolean calciteVisible() {
    return this != WRITE_FENCES;
  }

  String description() {
    return description;
  }

  /** The logical owner, shared across tenant models and deployment hosts. */
  public PhysicalObjectOwner owner() {
    // Namespace infrastructure is shared across models and tenants. Its identity must not depend
    // on whichever model first caused provisioning, or on a particular deployment host.
    return new PhysicalObjectOwner("dna-runtime", null, table);
  }

  PhysicalSchemaTarget target(PhysicalSchemaTarget model) {
    var source = model.metadataTarget();
    return new PhysicalSchemaTarget(
        table,
        new RuntimeMetadataTarget(
            source.dataSourceId(), source.catalogName(), source.schemaName(), table));
  }

  PhysicalSchemaDefinition definition() {
    return switch (this) {
      case OPERATIONS -> RecordJournalSchema.definition();
      case SNAPSHOTS -> RecordSnapshotSchema.definition();
      case REFERENCE_COPIES -> RecordReferenceCopySchema.definition();
      case WRITE_FENCES -> RecordWriteFenceSchema.definition();
      case CALCULATIONS -> RecordCalculationSchema.definition();
      case PUBLICATIONS -> RecordPublicationSchema.definition();
      case PUBLICATION_STAGES -> RecordPublicationStageSchema.definition();
      case PUBLICATION_CHUNKS -> RecordPublicationStageSchema.chunksDefinition();
    };
  }
}
