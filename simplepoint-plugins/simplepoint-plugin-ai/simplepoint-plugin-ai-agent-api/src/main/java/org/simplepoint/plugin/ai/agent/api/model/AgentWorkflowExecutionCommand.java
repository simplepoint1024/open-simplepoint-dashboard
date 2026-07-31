package org.simplepoint.plugin.ai.agent.api.model;

import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Internal command used by Workflow Runtime for one pinned Agent version.
 *
 * @param agentId pinned Agent definition
 * @param agentVersionId pinned immutable Agent version
 * @param expectedContentHash content hash captured by the Workflow version
 * @param executionScope Workflow ownership scope
 * @param tenantId Workflow tenant
 * @param requestedBy original Workflow requester
 * @param requestContextId original authorization context
 * @param idempotencyKey stable Workflow node key
 * @param input resolved node input
 */
public record AgentWorkflowExecutionCommand(
    String agentId,
    String agentVersionId,
    String expectedContentHash,
    AiResourceScope executionScope,
    String tenantId,
    String requestedBy,
    String requestContextId,
    String idempotencyKey,
    Map<String, Object> input
) {
}
