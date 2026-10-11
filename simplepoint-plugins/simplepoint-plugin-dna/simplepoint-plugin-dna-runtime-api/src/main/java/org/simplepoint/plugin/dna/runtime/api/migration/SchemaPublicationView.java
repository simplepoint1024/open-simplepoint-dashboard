package org.simplepoint.plugin.dna.runtime.api.migration;

import java.time.Instant;
import java.util.List;

/** Durable publication result returned for publish, status, and retry operations. */
public record SchemaPublicationView(
    String migrationId,
    String physicalModelId,
    Integer fromVersion,
    int targetVersion,
    SchemaMigrationStatus status,
    SchemaMigrationRisk risk,
    String dialectId,
    String actualChecksum,
    String targetChecksum,
    String errorCode,
    Instant startedAt,
    Instant finishedAt,
    List<SchemaMigrationStepView> steps
) {

  /** Makes the step projection immutable. */
  public SchemaPublicationView {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }
}
