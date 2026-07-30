package org.simplepoint.plugin.ai.mcp.api.model;

import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Trusted background request to render one snapshotted MCP Prompt.
 */
public record McpWorkflowPromptGetRequest(
    AiResourceScope invocationScope,
    String invocationTenantId,
    String serverId,
    String snapshotId,
    String promptName,
    String expectedDescriptorHash,
    Map<String, Object> arguments,
    String skillId,
    String skillVersionId,
    String executionId,
    String stepId,
    String subjectId,
    String capabilityToken
) implements McpWorkflowRequest {
}
