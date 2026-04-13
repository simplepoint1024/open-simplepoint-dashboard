package org.simplepoint.data.calcite.core.query;

import java.util.List;

/**
 * Query request passed to the Calcite execution engine.
 *
 * @param sql           SQL text
 * @param defaultSchema default schema name
 * @param maxRows       result row limit
 * @param timeoutMs     query timeout in milliseconds
 * @param parameters    optional bind parameters for server-side prepared execution
 */
public record CalciteQueryRequest(
    String sql,
    String defaultSchema,
    int maxRows,
    int timeoutMs,
    List<Object> parameters
) {

  public CalciteQueryRequest(
      final String sql,
      final String defaultSchema,
      final int maxRows,
      final int timeoutMs
  ) {
    this(sql, defaultSchema, maxRows, timeoutMs, null);
  }
}
