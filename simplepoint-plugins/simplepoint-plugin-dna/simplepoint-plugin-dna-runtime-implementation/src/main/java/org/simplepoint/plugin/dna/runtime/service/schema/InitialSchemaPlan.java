package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Complete initial publication plan plus before and target structure snapshots. */
public record InitialSchemaPlan(
    String dialectId,
    DatabaseIdentity database,
    PhysicalTableSnapshot actualSnapshot,
    PhysicalTableSnapshot targetSnapshot,
    SchemaMigrationRisk risk,
    List<PlannedSchemaStep> steps,
    List<String> checkpointChecksums
) {

  /** Source compatibility for legacy plans; durable execution requires checkpoints. */
  public InitialSchemaPlan(String dialectId, DatabaseIdentity database, PhysicalTableSnapshot actualSnapshot,
      PhysicalTableSnapshot targetSnapshot, SchemaMigrationRisk risk, List<PlannedSchemaStep> steps) {
    this(dialectId, database, actualSnapshot, targetSnapshot, risk, steps, List.of());
  }

  /** Makes the plan immutable. */
  public InitialSchemaPlan {
    if (dialectId == null || dialectId.isBlank() || database == null
        || actualSnapshot == null || targetSnapshot == null || risk == null) {
      throw new IllegalArgumentException("Initial schema plan is incomplete");
    }
    steps = steps == null ? List.of() : List.copyOf(steps);
    checkpointChecksums = checkpointChecksums == null ? List.of() : List.copyOf(checkpointChecksums);
    if (!checkpointChecksums.isEmpty() && (checkpointChecksums.size() != steps.size() + 1
        || checkpointChecksums.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))
        || !checkpointChecksums.getFirst().equals(actualSnapshot.checksum())
        || !checkpointChecksums.getLast().equals(targetSnapshot.checksum()))) {
      throw new IllegalArgumentException("Schema plan checkpoint sequence is invalid");
    }
  }
}
