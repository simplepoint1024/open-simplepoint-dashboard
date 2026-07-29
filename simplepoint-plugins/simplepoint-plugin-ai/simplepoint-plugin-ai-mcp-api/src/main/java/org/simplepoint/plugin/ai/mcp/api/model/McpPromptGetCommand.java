package org.simplepoint.plugin.ai.mcp.api.model;

import java.util.Map;

/**
 * Workbench command to render a snapshotted MCP prompt.
 */
public record McpPromptGetCommand(
    String name,
    Map<String, Object> arguments
) {
}
