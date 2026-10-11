package org.simplepoint.data.calcite.core.query;

import java.util.List;

/** Affected rows and physical SQL captured from a Calcite modification. */
public record CalciteUpdateResult(
    int affectedRows,
    long executionTimeMs,
    List<String> pushedSqls
) {}
