package org.simplepoint.plugin.dna.core.api.vo;

import java.time.Instant;

/**
 * Result of testing or establishing a datasource connection.
 */
public record JdbcDataSourceConnectionResult(
    String dataSourceId,
    String dataSourceCode,
    String driverId,
    String driverCode,
    boolean connected,
    Instant testedAt,
    String message,
    String databaseProductName,
    String databaseProductVersion,
    String jdbcUrl
) {
}
