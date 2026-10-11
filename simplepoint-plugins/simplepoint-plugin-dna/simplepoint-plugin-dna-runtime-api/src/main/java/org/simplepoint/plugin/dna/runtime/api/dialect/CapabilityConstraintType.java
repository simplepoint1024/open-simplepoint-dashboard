package org.simplepoint.plugin.dna.runtime.api.dialect;

/** Machine-readable dimensions used to constrain a dialect capability. */
public enum CapabilityConstraintType {
  MIN_DATABASE_VERSION,
  MAX_DATABASE_VERSION,
  MAX_IDENTIFIER_LENGTH,
  MAX_PARAMETER_COUNT,
  REQUIRES_EMPTY_TABLE,
  REQUIRES_TABLE_REBUILD,
  LOCK_LEVEL,
  SUPPORTED_LOGICAL_TYPES,
  SUPPORTED_ISOLATION_LEVELS,
  DDL_TRANSACTION_MODE,
  ONLINE_MODE,
  RETURNING_MODE
}
