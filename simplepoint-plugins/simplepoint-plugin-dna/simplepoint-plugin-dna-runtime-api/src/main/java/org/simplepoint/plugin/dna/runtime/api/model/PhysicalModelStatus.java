package org.simplepoint.plugin.dna.runtime.api.model;

/** Lifecycle state of a physical form model. */
public enum PhysicalModelStatus {
  DRAFT,
  VALIDATING,
  READY,
  MIGRATING,
  VERIFYING,
  ACTIVE,
  FAILED,
  RETIRED
}
