package org.simplepoint.plugin.ai.mcp.api.model;

import java.time.Instant;

/**
 * Safe workbench summary of one immutable MCP capability snapshot.
 */
public record McpCapabilitySnapshotSummary(
    String id,
    String serverId,
    String protocolVersion,
    String remoteServerName,
    String remoteServerVersion,
    String schemaHash,
    Instant discoveredAt,
    boolean active,
    int toolCount,
    int resourceCount,
    int resourceTemplateCount,
    int promptCount
) {
}
