package org.simplepoint.plugin.ai.mcp.api.model;

import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** Non-secret, bounded catalog projection; no credentials, OAuth state or capability documents. */
public record McpCatalogRegistration(
    String id,
    String code,
    String version,
    String name,
    String description,
    McpServerStatus status,
    AiResourceScope scopeType,
    String endpointUrl,
    Instant updatedAt
) {
}
