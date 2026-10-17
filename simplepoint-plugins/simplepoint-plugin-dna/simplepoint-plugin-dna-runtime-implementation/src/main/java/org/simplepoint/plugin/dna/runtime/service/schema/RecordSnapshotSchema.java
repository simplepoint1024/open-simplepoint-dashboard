package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Separate immutable business snapshots; operation receipts continue to contain metadata only. */
public final class RecordSnapshotSchema {
  public static final String TABLE = "simpoint_dna_record_snapshots_v1";

  private RecordSnapshotSchema() {}

  /** Defines the bounded immutable-snapshot journal independently of business fields. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            field("model_id", LogicalFieldType.STRING, 64),
            field("operation_key", LogicalFieldType.STRING, 128),
            field("request_fingerprint", LogicalFieldType.STRING, 64),
            field("snapshot_json", LogicalFieldType.TEXT, null)),
        List.of());
  }

  /** Places the infrastructure table in the same datasource and namespace as its business model. */
  public static PhysicalSchemaTarget target(PhysicalSchemaTarget model) {
    var source = model.metadataTarget();
    return new PhysicalSchemaTarget(
        TABLE,
        new RuntimeMetadataTarget(
            source.dataSourceId(), source.catalogName(), source.schemaName(), TABLE));
  }

  private static LogicalFieldDefinition field(String code, LogicalFieldType type, Integer length) {
    return new LogicalFieldDefinition(code, code, code, type, false, length, null, null, null);
  }
}
