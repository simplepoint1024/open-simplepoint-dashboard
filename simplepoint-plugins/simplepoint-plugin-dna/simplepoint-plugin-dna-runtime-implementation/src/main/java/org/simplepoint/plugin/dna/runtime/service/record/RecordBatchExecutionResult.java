package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;

/** Only the committed attempt supplies mutation receipts and pushdown observations. */
record RecordBatchExecutionResult(
    List<Integer> affectedRows,
    long executionTimeMs,
    int pushedStatementCount,
    List<RecordCommandResult> results,
    boolean replayed) {
  RecordBatchExecutionResult {
    affectedRows = List.copyOf(affectedRows);
    results = List.copyOf(results);
    if (results.isEmpty()) {
      throw new IllegalArgumentException("A committed batch requires mutation receipts");
    }
  }

  List<RecordCommandResult> replayedResults() {
    return replayed ? results : null;
  }
}
