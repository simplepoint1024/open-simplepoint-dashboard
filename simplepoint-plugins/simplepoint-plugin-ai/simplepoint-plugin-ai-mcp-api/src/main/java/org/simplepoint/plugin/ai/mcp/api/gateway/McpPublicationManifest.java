package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;

/**
 * Immutable northbound MCP publication manifest fetched by Gateway replicas.
 */
public record McpPublicationManifest(
    String code,
    String name,
    String description,
    String canonicalResourceUri,
    String authorizationServerUri,
    List<String> requiredScopes,
    int rateLimitPerMinute,
    String upstreamServerId,
    String snapshotId,
    String protocolVersion,
    List<McpToolDescriptor> tools,
    List<McpResourceDescriptor> resources,
    List<McpResourceTemplateDescriptor> resourceTemplates,
    List<McpPromptDescriptor> prompts
) {
}
