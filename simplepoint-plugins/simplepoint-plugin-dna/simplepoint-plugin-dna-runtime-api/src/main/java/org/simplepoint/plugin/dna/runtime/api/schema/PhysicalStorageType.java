package org.simplepoint.plugin.dna.runtime.api.schema;

/** Database-neutral physical storage categories used for structure verification. */
public enum PhysicalStorageType {
  STRING,
  TEXT,
  BOOLEAN,
  INT32,
  INT64,
  DECIMAL,
  DATE,
  TIME,
  DATETIME,
  UUID,
  JSON,
  BINARY
}
