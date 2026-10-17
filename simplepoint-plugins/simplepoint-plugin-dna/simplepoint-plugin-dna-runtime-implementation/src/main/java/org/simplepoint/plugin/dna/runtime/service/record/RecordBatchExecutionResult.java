package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;

/** Committed mutation results and sanitized Calcite pushdown observation counts. */
record RecordBatchExecutionResult(
    List<Integer> affectedRows,
    long executionTimeMs,
    int pushedStatementCount,
    List<RecordCommandResult> replayedResults
) {

  RecordBatchExecutionResult(List<Integer> affectedRows, long executionTimeMs, int pushedStatementCount) {
    this(affectedRows, executionTimeMs, pushedStatementCount, null);
  }

  RecordBatchExecutionResult {
    affectedRows = List.copyOf(affectedRows);
    replayedResults = replayedResults == null ? null : List.copyOf(replayedResults);
  }
}
