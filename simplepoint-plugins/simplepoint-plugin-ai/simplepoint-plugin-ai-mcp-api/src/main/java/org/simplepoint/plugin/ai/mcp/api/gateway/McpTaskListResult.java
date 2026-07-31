package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;

/**
 * Cursor-paginated standard MCP Task list.
 */
public record McpTaskListResult(
    List<McpTaskDescriptor> tasks,
    String nextCursor
) {
}
