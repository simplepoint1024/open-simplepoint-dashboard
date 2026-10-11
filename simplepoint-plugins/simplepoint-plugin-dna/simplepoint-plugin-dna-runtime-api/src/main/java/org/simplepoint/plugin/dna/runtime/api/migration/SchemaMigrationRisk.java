package org.simplepoint.plugin.dna.runtime.api.migration;

/** Highest compatibility risk introduced by a schema migration. */
public enum SchemaMigrationRisk {
  SAFE,
  CONDITIONAL,
  DESTRUCTIVE
}
