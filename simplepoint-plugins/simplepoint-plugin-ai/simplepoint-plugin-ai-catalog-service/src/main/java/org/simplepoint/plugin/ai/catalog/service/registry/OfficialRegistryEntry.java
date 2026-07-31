package org.simplepoint.plugin.ai.catalog.service.registry;

import java.time.Instant;

/**
 * Validated official MCP Registry record.
 */
public record OfficialRegistryEntry(
    String name,
    String version,
    String title,
    String description,
    String status,
    String transportType,
    String endpointUrl,
    String repositoryUrl,
    String websiteUrl,
    Instant publishedAt,
    Instant updatedAt,
    String rawJson
) {
}
