package org.simplepoint.plugin.dna.runtime.api.migration;

/** Structured operation kinds persisted for schema publication recovery. */
public enum SchemaMigrationOperation {
  CREATE_TABLE,
  ADD_COLUMN,
  RENAME_COLUMN,
  ALTER_COLUMN,
  DROP_COLUMN,
  CREATE_INDEX,
  DROP_INDEX,
  COMMENT_TABLE,
  COMMENT_COLUMN
}
