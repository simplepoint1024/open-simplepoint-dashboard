package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;

/** Authorized immutable inputs reused by create execution and recovery probes. */
record PreparedRecordTransaction(
    RecordTransactionCommand command,
    RuntimeRecordModel model,
    RecordAccessContext access,
    RecordCalculationWriteSupport.Binding calculation,
    java.util.Map<String, RecordCalculationWriteSupport.Scope> calculationChildren
) {
  PreparedRecordTransaction(final RecordTransactionCommand command, final RuntimeRecordModel model, final RecordAccessContext access) {
    this(command, model, access, null, java.util.Map.of());
  }

  PreparedRecordTransaction {
    calculationChildren = java.util.Map.copyOf(calculationChildren);
  }
}
