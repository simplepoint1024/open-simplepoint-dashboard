package org.simplepoint.plugin.dna.runtime.api.schema;

/** Canonical wire representation of a logical value. */
public enum LogicalValueKind {
  NULL,
  STRING,
  BOOLEAN,
  INTEGER,
  DECIMAL,
  DATE,
  TIME,
  DATETIME,
  UUID,
  JSON,
  BINARY
}
