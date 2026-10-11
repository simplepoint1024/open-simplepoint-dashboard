package org.simplepoint.plugin.dna.runtime.service.schema;

import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;

/** Internal native statement produced only by a trusted Runtime dialect. */
public record PlannedSchemaStep(
    int sequence,
    SchemaMigrationOperation operation,
    String logicalObject,
    String satisfactionKey,
    SchemaMigrationRisk risk,
    String payloadJson,
    String statementSql,
    String statementChecksum
) {

  /** Validates persisted recovery material. */
  public PlannedSchemaStep {
    if (sequence < 1 || operation == null || risk == null) {
      throw new IllegalArgumentException("Migration step identity is invalid");
    }
    if (logicalObject == null || logicalObject.isBlank()) {
      throw new IllegalArgumentException("logicalObject is required");
    }
    if (satisfactionKey == null || satisfactionKey.isBlank()) {
      throw new IllegalArgumentException("satisfactionKey is required");
    }
    if (payloadJson == null || payloadJson.isBlank()) {
      throw new IllegalArgumentException("payloadJson is required");
    }
    if (statementSql == null || statementSql.isBlank()) {
      throw new IllegalArgumentException("statementSql is required");
    }
    if (statementChecksum == null || !statementChecksum.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("statementChecksum must be a SHA-256 value");
    }
  }
}
