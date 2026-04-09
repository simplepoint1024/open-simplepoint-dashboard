package org.simplepoint.data.calcite.core.query;

/**
 * Query request passed to the Calcite execution engine.
 *
 * @param sql           SQL text
 * @param defaultSchema default schema name
 * @param maxRows       result row limit
 * @param timeoutMs     query timeout in milliseconds
 */
public record CalciteQueryRequest(
    String sql,
    String defaultSchema,
    int maxRows,
    int timeoutMs
) {
}
