package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Cluster-visible MCP event types emitted by a connected upstream server.
 */
public enum McpGatewayEventType {

  TOOLS_LIST_CHANGED,

  RESOURCES_LIST_CHANGED,

  PROMPTS_LIST_CHANGED,

  RESOURCE_UPDATED
}
