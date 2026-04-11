package org.simplepoint.plugin.dna.core.api.vo;

/**
 * Health status of a single datasource.
 */
public record DataSourceHealthStatus(
    String dataSourceId,
    String dataSourceCode,
    String dataSourceName,
    String driverName,
    String status,
    long responseTimeMs,
    String errorMessage
) {
}
