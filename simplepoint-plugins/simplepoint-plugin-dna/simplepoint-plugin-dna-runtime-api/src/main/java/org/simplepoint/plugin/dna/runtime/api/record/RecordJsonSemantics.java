package org.simplepoint.plugin.dna.runtime.api.record;

/** Explicitly versions JSON missing-value checks without changing durable legacy inspection intents. */
public enum RecordJsonSemantics {
  /** Includes JSON null, empty containers and Unicode-blank JSON strings in missing values. */
  STRUCTURAL_V1
}
