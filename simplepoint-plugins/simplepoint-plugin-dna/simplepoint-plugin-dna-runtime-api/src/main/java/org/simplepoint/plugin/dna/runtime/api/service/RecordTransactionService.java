package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionResult;

/** Atomic bounded mutation groups for one published physical model. */
public interface RecordTransactionService {

  /** Returns a committed result for the exact keyed intent, or null; never executes mutations. */
  RecordTransactionResult findResult(RecordTransactionCommand command);

  /** Executes all mutations on one physical connection and commits only after every step succeeds. */
  RecordTransactionResult execute(RecordTransactionCommand command);
}
