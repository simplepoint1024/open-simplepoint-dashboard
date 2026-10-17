package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Transactional admission metadata in the same namespace as the records it protects. */
public final class RecordWriteFenceSchema {

  public static final String TABLE = "simpoint_dna_record_write_fences_v1";

  private RecordWriteFenceSchema() {
  }

  /** Contains no business values; uses the platform schema planner and standard audit columns. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        field("model_id", LogicalFieldType.STRING, 64),
        field("write_revision", LogicalFieldType.INT64, null),
        field("previous_revision", LogicalFieldType.INT64, null),
        field("publication_fingerprint", LogicalFieldType.STRING, 64)), List.of());
  }

  /** Resolves only the server-owned infrastructure table beside a managed physical model. */
  public static PhysicalSchemaTarget target(final PhysicalSchemaTarget model) {
    RuntimeMetadataTarget source = model.metadataTarget();
    return new PhysicalSchemaTarget(TABLE, new RuntimeMetadataTarget(source.dataSourceId(),
        source.catalogName(), source.schemaName(), TABLE));
  }

  private static LogicalFieldDefinition field(final String code, final LogicalFieldType type, final Integer length) {
    return new LogicalFieldDefinition(code, code, code, type, false, length, null, null, null);
  }
}
