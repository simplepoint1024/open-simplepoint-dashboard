package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Current protected origin ledger, colocated with source rows and their transaction receipts. */
public final class RecordReferenceCopySchema {
  public static final String TABLE = "simpoint_dna_reference_copies_v1";

  private RecordReferenceCopySchema() {}

  /** One bounded origin set per source record; actual copied values remain in business columns. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            field("model_id", LogicalFieldType.STRING, 64),
            field("source_record_id", LogicalFieldType.UUID, null),
            field("origins_json", LogicalFieldType.TEXT, null)),
        List.of());
  }

  private static LogicalFieldDefinition field(String code, LogicalFieldType type, Integer length) {
    return new LogicalFieldDefinition(code, code, code, type, false, length, null, null, null);
  }
}
