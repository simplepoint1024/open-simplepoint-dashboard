package org.simplepoint.plugin.ai.mcp.api.model;

/**
 * Durable state projected onto the MCP Tasks lifecycle.
 */
public enum McpTaskStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED
}
