package org.simplepoint.plugin.ai.mcp.api.model;

import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Trusted background request to read one snapshotted MCP Resource.
 */
public record McpWorkflowResourceReadRequest(
    AiResourceScope invocationScope,
    String invocationTenantId,
    String serverId,
    String snapshotId,
    String uri,
    String boundSelector,
    boolean resourceTemplate,
    String expectedDescriptorHash,
    String skillId,
    String skillVersionId,
    String executionId,
    String stepId,
    String subjectId,
    String capabilityToken
) implements McpWorkflowRequest {
}
