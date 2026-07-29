package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * SDK-neutral MCP tool result.
 *
 * @param content MCP content blocks
 * @param error whether the MCP server reported a tool execution error
 * @param structuredContent optional structured result
 * @param meta optional protocol metadata
 */
public record McpGatewayToolCallResult(
    List<Map<String, Object>> content,
    boolean error,
    Object structuredContent,
    Map<String, Object> meta
) {
}
