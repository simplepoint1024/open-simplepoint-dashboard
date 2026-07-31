package org.simplepoint.plugin.ai.catalog.api.model;

import java.time.Instant;

/**
 * Result of one bounded official registry synchronization run.
 */
public record AiCatalogSyncResult(
    CatalogSource source,
    CatalogSyncMode mode,
    CatalogSyncStatus status,
    long syncedCount,
    String nextCursor,
    Instant completedAt,
    String error
) {
}
