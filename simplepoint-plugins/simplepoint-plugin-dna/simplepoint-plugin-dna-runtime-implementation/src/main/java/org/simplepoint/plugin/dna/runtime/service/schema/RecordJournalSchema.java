package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Versioned, platform-owned receipt table in each target namespace; it contains no business values. */
public final class RecordJournalSchema {
  public static final String TABLE = "simpoint_dna_record_operations_v1";

  private RecordJournalSchema() {
  }

  /** Uses the same dialect planner and system columns as managed record tables. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        field("model_id", LogicalFieldType.STRING, 64),
        field("operation_key", LogicalFieldType.STRING, 128),
        field("request_fingerprint", LogicalFieldType.STRING, 64),
        field("result_json", LogicalFieldType.TEXT, null)
    ), List.of());
  }

  /** Keeps the namespace server-owned, sharing one journal across models in that namespace. */
  public static PhysicalSchemaTarget target(final PhysicalSchemaTarget model) {
    RuntimeMetadataTarget source = model.metadataTarget();
    return new PhysicalSchemaTarget(TABLE, new RuntimeMetadataTarget(source.dataSourceId(),
        source.catalogName(), source.schemaName(), TABLE));
  }

  private static LogicalFieldDefinition field(String code, LogicalFieldType type, Integer length) {
    return new LogicalFieldDefinition(code, code, code, type, false, length, null, null, null);
  }
}
