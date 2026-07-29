package org.simplepoint.plugin.ai.mcp.api.model;

import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Trusted background request to invoke an exact snapshotted MCP Tool.
 *
 * @param invocationScope scope that owns the durable workflow execution
 * @param invocationTenantId tenant that owns the workflow execution
 * @param serverId pinned MCP Server
 * @param snapshotId pinned immutable capability snapshot
 * @param toolName exact Tool name
 * @param expectedInputSchemaHash schema hash persisted by the Skill version
 * @param arguments resolved Tool input
 * @param executionId durable Skill execution ID used for session affinity
 * @param stepId durable workflow step ID
 * @param subjectId original user that submitted the workflow
 */
public record McpWorkflowToolCallRequest(
    AiResourceScope invocationScope,
    String invocationTenantId,
    String serverId,
    String snapshotId,
    String toolName,
    String expectedInputSchemaHash,
    Map<String, Object> arguments,
    String executionId,
    String stepId,
    String subjectId
) {
}
