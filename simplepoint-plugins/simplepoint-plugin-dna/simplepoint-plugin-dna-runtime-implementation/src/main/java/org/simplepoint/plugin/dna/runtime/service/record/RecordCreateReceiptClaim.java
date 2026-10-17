package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;

/** Durable create identity, operation time, and optional completed result. */
public record RecordCreateReceiptClaim(
    String recordId,
    Instant operationAt,
    RecordCommandResult completedResult
) {
}
