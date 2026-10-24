package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Separate aggregate manifests and immutable chunks; no pending values enter installed definitions. */
public final class RecordPublicationStageSchema {
  public static final String TABLE = "simpoint_dna_publication_stages_v1";
  public static final String CHUNKS = "simpoint_dna_publication_chunks_v1";

  private RecordPublicationStageSchema() {
  }

  /** record_version is the STAGED/APPLIED/RESTORED sequence; payload columns never change. */
  public static PhysicalSchemaDefinition definition() {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        text("model_id", 64), number("target_revision", LogicalFieldType.INT64), text("publication_id", 64),
        text("publication_fingerprint", 64), text("intent_checksum", 64), text("parent_record_id", 64), text("phase", 16),
        text("payload_checksum", 64), number("payload_bytes", LogicalFieldType.INT64), number("chunk_count", LogicalFieldType.INT32)),
        List.of(new LogicalIndexDefinition("idx_publication_stage_cursor", List.of("publication_id", "phase", "parent_record_id"), false)));
  }

  /** Small chunks stay below both supported JDBC/Calcite text projection limits. */
  public static PhysicalSchemaDefinition chunksDefinition() {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(text("stage_id", 64),
        number("chunk_index", LogicalFieldType.INT32), text("chunk_checksum", 64),
        new LogicalFieldDefinition("chunk_text", "chunk_text", "chunk_text", LogicalFieldType.TEXT, false, null, null, null, null)),
        List.of(new LogicalIndexDefinition("uq_publication_chunk", List.of("stage_id", "chunk_index"), true)));
  }

  /** Uses the same trusted namespace as the owning model. */
  public static PhysicalSchemaTarget target(final PhysicalSchemaTarget model, final boolean chunks) {
    var source = model.metadataTarget();
    String table = chunks ? CHUNKS : TABLE;
    return new PhysicalSchemaTarget(table, new RuntimeMetadataTarget(source.dataSourceId(), source.catalogName(), source.schemaName(), table));
  }

  private static LogicalFieldDefinition text(final String name, final int length) {
    return new LogicalFieldDefinition(name, name, name, LogicalFieldType.STRING, false, length, null, null, null);
  }

  private static LogicalFieldDefinition number(final String name, final LogicalFieldType type) {
    return new LogicalFieldDefinition(name, name, name, type, false, null, null, null, null);
  }
}
