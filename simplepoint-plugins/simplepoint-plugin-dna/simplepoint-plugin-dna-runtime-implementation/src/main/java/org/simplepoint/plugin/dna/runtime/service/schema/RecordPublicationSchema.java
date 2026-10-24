package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Pending/terminal publication decisions, separate from installed calculation definitions. */
public final class RecordPublicationSchema {
  public static final String TABLE = "simpoint_dna_record_publications_v1";

  private RecordPublicationSchema() {
  }

  /** record_version is the durable monotonically increasing checkpoint sequence. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        field("model_id", LogicalFieldType.STRING, 64),
        field("target_revision", LogicalFieldType.INT64, null),
        field("publication_fingerprint", LogicalFieldType.STRING, 64),
        field("phase", LogicalFieldType.STRING, 16),
        field("intent_checksum", LogicalFieldType.STRING, 64),
        field("intent_json", LogicalFieldType.TEXT, null),
        field("checkpoint_checksum", LogicalFieldType.STRING, 64),
        field("checkpoint_json", LogicalFieldType.TEXT, null)), List.of(
            new LogicalIndexDefinition("idx_publication_model", List.of("model_id", "target_revision"), false)));
  }

  /** Uses the owning model's trusted placement; there is no second coordinator database. */
  public static PhysicalSchemaTarget target(final PhysicalSchemaTarget model) {
    var source = model.metadataTarget();
    return new PhysicalSchemaTarget(TABLE, new RuntimeMetadataTarget(source.dataSourceId(),
        source.catalogName(), source.schemaName(), TABLE));
  }

  private static LogicalFieldDefinition field(final String code, final LogicalFieldType type, final Integer length) {
    return new LogicalFieldDefinition(code, code, code, type, false, length, null, null, null);
  }
}
