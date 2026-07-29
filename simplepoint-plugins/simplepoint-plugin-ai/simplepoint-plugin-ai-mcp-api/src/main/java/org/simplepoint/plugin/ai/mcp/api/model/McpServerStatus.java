package org.simplepoint.plugin.ai.mcp.api.model;

/**
 * Control-plane lifecycle state of an MCP server registration.
 */
public enum McpServerStatus {
  DRAFT,
  READY,
  ERROR,
  DISABLED
}
