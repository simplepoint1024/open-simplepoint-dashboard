package org.simplepoint.plugin.dna.runtime.api.migration;

import java.time.Instant;

/** Safe migration step projection that does not expose native SQL or physical names. */
public record SchemaMigrationStepView(
    int sequence,
    SchemaMigrationOperation operation,
    String logicalObject,
    SchemaMigrationRisk risk,
    SchemaMigrationStepStatus status,
    int attempts,
    String errorCode,
    Instant startedAt,
    Instant finishedAt
) {
}
