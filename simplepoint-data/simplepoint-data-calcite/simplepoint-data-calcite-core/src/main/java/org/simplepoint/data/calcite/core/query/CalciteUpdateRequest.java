package org.simplepoint.data.calcite.core.query;

import java.util.List;

/** A single data modification executed against a Calcite JDBC catalog. */
public record CalciteUpdateRequest(
    String sql,
    String defaultSchema,
    int timeoutMs,
    List<Object> parameters
) {}
