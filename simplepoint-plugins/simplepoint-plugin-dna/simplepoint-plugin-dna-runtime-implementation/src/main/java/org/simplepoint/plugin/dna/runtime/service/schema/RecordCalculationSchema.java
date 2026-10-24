package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Immutable calculation definitions committed beside the write fence and protected records. */
public final class RecordCalculationSchema {
  public static final String TABLE = "simpoint_dna_record_calculations_v1";

  private RecordCalculationSchema() {
  }

  /** Contains definitions and identities only, never record inputs or authorization snapshots. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        field("model_id", LogicalFieldType.STRING, 64),
        field("policy_revision", LogicalFieldType.INT64, null),
        field("definition_checksum", LogicalFieldType.STRING, 64),
        field("definition_json", LogicalFieldType.TEXT, null)), List.of(
            new LogicalIndexDefinition("idx_calc_model_revision", List.of("model_id", "policy_revision"), false)));
  }

  /** Uses the model's server-resolved datasource and namespace. */
  public static PhysicalSchemaTarget target(final PhysicalSchemaTarget model) {
    var source = model.metadataTarget();
    return new PhysicalSchemaTarget(TABLE, new RuntimeMetadataTarget(source.dataSourceId(),
        source.catalogName(), source.schemaName(), TABLE));
  }

  private static LogicalFieldDefinition field(final String code, final LogicalFieldType type, final Integer length) {
    return new LogicalFieldDefinition(code, code, code, type, false, length, null, null, null);
  }
}
