package org.simplepoint.plugin.dna.core.api.vo;

import java.time.Instant;

/**
 * Result of downloading a JDBC driver artifact.
 */
public record JdbcDriverDownloadResult(
    String driverId,
    String driverCode,
    String localJarPath,
    String driverClassName,
    String jdbcUrlPattern,
    String version,
    Instant downloadedAt,
    String message
) {
}
