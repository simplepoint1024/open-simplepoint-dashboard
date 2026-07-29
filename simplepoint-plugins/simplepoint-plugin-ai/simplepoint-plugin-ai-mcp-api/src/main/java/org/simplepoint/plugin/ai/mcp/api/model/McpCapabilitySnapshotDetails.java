package org.simplepoint.plugin.ai.mcp.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;

/**
 * Safe decoded workbench view of one immutable MCP capability snapshot.
 */
public record McpCapabilitySnapshotDetails(
    String id,
    String serverId,
    String protocolVersion,
    String remoteServerName,
    String remoteServerVersion,
    String schemaHash,
    Instant discoveredAt,
    boolean active,
    Map<String, Object> capabilities,
    List<McpToolDescriptor> tools,
    List<McpResourceDescriptor> resources,
    List<McpResourceTemplateDescriptor> resourceTemplates,
    List<McpPromptDescriptor> prompts
) {
}
