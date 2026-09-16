package org.simplepoint.plugin.ai.skill.api.model;

import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Internal command for cancelling one exact published child Skill execution.
 *
 * @param skillId pinned Skill definition
 * @param skillVersionId pinned immutable Skill version
 * @param executionId exact child execution
 * @param executionScope parent execution ownership scope
 * @param tenantId parent execution tenant
 * @param idempotencyKey stable key used to create the child execution
 * @param actorId durable parent or runtime actor identity
 * @param reason cancellation reason retained on the child execution
 */
public record SkillPinnedChildCancelCommand(
    String skillId,
    String skillVersionId,
    String executionId,
    AiResourceScope executionScope,
    String tenantId,
    String idempotencyKey,
    String actorId,
    String reason
) {
}
