package org.simplepoint.plugin.dna.runtime.api.record;

/** Nonterminal phases of the existing write-policy publication operation. */
public enum RecordWritePolicyPhase {
  /** Persist candidate results while ordinary aggregate access is fenced. */
  STAGING,
  /** Apply staged results with an undo journal before final activation. */
  APPLYING,
  /** Restore journaled values before confirming cancellation and releasing maintenance. */
  RESTORING
}
