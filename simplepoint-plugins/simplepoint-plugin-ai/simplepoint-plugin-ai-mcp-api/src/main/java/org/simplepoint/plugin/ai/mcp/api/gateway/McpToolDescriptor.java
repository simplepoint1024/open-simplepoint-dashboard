package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * Stable, SDK-neutral description of an MCP tool.
 *
 * @param name tool protocol name
 * @param title optional display title
 * @param description optional description
 * @param inputSchema input JSON Schema
 * @param outputSchema optional output JSON Schema
 * @param annotations untrusted MCP annotations
 * @param icons optional MCP icon descriptors
 */
public record McpToolDescriptor(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    Map<String, Object> annotations,
    List<Map<String, Object>> icons
) {
}
