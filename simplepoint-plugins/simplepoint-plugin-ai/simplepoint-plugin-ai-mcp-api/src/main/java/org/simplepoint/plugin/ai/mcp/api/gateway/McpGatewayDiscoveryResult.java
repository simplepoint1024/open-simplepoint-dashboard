package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * Result of initializing an MCP connection and discovering its tool capability.
 *
 * @param protocolVersion negotiated MCP protocol version
 * @param serverName server implementation name
 * @param serverTitle optional server display title
 * @param serverVersion server implementation version
 * @param serverDescription optional server description
 * @param instructions optional MCP server instructions
 * @param capabilities negotiated server capabilities
 * @param tools fully paginated tool list
 * @param resources fully paginated resource list
 * @param resourceTemplates fully paginated resource-template list
 * @param prompts fully paginated prompt list
 */
public record McpGatewayDiscoveryResult(
    String protocolVersion,
    String serverName,
    String serverTitle,
    String serverVersion,
    String serverDescription,
    String instructions,
    Map<String, Object> capabilities,
    List<McpToolDescriptor> tools,
    List<McpResourceDescriptor> resources,
    List<McpResourceTemplateDescriptor> resourceTemplates,
    List<McpPromptDescriptor> prompts
) {
}
