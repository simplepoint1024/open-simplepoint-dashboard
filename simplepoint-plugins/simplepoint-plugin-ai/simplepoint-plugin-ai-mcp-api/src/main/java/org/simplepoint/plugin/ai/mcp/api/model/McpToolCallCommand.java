package org.simplepoint.plugin.ai.mcp.api.model;

import java.util.Map;

/**
 * External management command for testing an MCP tool through its registered server.
 *
 * @param toolName exact discovered tool name
 * @param arguments JSON arguments
 */
public record McpToolCallCommand(String toolName, Map<String, Object> arguments) {
}
