package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * Stable, SDK-neutral MCP resource descriptor.
 */
public record McpResourceDescriptor(
    String uri,
    String name,
    String title,
    String description,
    String mimeType,
    Long size,
    Map<String, Object> annotations,
    Map<String, Object> meta,
    List<Map<String, Object>> icons
) {
}
