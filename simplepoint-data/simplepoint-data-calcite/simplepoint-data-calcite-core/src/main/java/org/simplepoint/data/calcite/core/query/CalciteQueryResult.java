package org.simplepoint.data.calcite.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Query execution result with rows, metadata, and explain analysis.
 *
 * @param columns         result-set columns
 * @param rows            result rows
 * @param truncated       whether more rows existed beyond the configured limit
 * @param returnedRows    number of returned rows
 * @param executionTimeMs execution time in milliseconds
 * @param analysis        explain-plan analysis
 */
public record CalciteQueryResult(
    List<CalciteQueryColumn> columns,
    List<List<Object>> rows,
    boolean truncated,
    long returnedRows,
    long executionTimeMs,
    CalciteQueryAnalysis analysis
) {

  /**
   * Creates an immutable result payload while preserving nullable cell values.
   *
   * @param columns         result-set columns
   * @param rows            result rows
   * @param truncated       whether more rows existed beyond the configured limit
   * @param returnedRows    number of returned rows
   * @param executionTimeMs execution time in milliseconds
   * @param analysis        explain-plan analysis
   */
  public CalciteQueryResult {
    columns = columns == null ? List.of() : List.copyOf(columns);
    rows = rows == null ? List.of() : rows.stream()
        .map(row -> row == null ? List.<Object>of() : Collections.unmodifiableList(new ArrayList<>(row)))
        .toList();
  }
}
