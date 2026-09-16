package org.simplepoint.plugin.ai.catalog.service.registry;

import java.time.Instant;
import org.simplepoint.plugin.ai.catalog.api.model.McpServerDescriptor;

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
    McpServerDescriptor descriptor,
    String rawJson
) {
}
