package org.simplepoint.plugin.dna.runtime.api.migration;

/** Durable lifecycle of one schema publication attempt. */
public enum SchemaMigrationStatus {
  VALIDATING,
  READY,
  MIGRATING,
  VERIFYING,
  ACTIVE,
  FAILED;

  /** Returns whether the migration has reached a terminal state. */
  public boolean terminal() {
    return this == ACTIVE || this == FAILED;
  }
}
