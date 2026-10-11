package org.simplepoint.plugin.dna.runtime.api.migration;

/** Durable execution state of one idempotent migration step. */
public enum SchemaMigrationStepStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  SKIPPED,
  FAILED
}
