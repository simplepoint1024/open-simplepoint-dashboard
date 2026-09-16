package org.simplepoint.plugin.ai.agent.api.model;

import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Internal command for cancelling one exact pinned child Agent execution.
 *
 * @param agentId pinned Agent definition
 * @param agentVersionId pinned immutable Agent version
 * @param executionId exact child execution
 * @param executionScope parent execution ownership scope
 * @param tenantId parent execution tenant
 * @param idempotencyKey stable key used to create the child execution
 * @param actorId durable parent or runtime actor identity
 * @param reason cancellation reason retained in the Agent trace
 */
public record AgentPinnedChildCancelCommand(
    String agentId,
    String agentVersionId,
    String executionId,
    AiResourceScope executionScope,
    String tenantId,
    String idempotencyKey,
    String actorId,
    String reason
) {
}
