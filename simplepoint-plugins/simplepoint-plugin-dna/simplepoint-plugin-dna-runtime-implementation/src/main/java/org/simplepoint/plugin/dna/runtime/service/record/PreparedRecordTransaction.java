package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;

/** Authorized immutable inputs reused by create execution and recovery probes. */
record PreparedRecordTransaction(
    RecordTransactionCommand command,
    RuntimeRecordModel model,
    RecordAccessContext access
) {
}
