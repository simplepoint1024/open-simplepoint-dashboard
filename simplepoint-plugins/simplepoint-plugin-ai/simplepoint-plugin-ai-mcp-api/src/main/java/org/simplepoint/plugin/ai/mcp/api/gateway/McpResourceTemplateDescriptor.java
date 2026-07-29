package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * Stable, SDK-neutral MCP resource-template descriptor.
 */
public record McpResourceTemplateDescriptor(
    String uriTemplate,
    String name,
    String title,
    String description,
    String mimeType,
    Map<String, Object> annotations,
    Map<String, Object> meta,
    List<Map<String, Object>> icons
) {
}
