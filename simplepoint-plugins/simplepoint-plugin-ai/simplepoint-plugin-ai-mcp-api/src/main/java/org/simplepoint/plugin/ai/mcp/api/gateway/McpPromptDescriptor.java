package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * Stable, SDK-neutral MCP prompt descriptor.
 */
public record McpPromptDescriptor(
    String name,
    String title,
    String description,
    List<Map<String, Object>> arguments,
    Map<String, Object> meta,
    List<Map<String, Object>> icons
) {
}
