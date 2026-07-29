package org.simplepoint.plugin.ai.mcp.api.model;

/**
 * Authorization state of an OAuth protected remote MCP connection.
 */
public enum McpOauthStatus {
  NOT_CONFIGURED,
  CONFIGURED,
  AUTHORIZATION_PENDING,
  CONNECTED,
  EXPIRED,
  ERROR
}
