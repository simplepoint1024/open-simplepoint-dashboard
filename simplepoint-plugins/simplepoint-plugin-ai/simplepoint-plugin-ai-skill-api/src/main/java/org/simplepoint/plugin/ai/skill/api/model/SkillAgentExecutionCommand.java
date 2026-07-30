package org.simplepoint.plugin.ai.skill.api.model;

import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Internal command used by Agent Runtime to execute one pinned Skill version.
 *
 * @param skillId pinned Skill definition
 * @param skillVersionId pinned immutable version
 * @param expectedContentHash content hash captured by the Agent version
 * @param executionScope Agent execution ownership scope
 * @param tenantId Agent execution tenant
 * @param requestedBy original Agent requester
 * @param idempotencyKey stable Agent tool-call key
 * @param input model-generated Skill input
 */
public record SkillAgentExecutionCommand(
    String skillId,
    String skillVersionId,
    String expectedContentHash,
    AiResourceScope executionScope,
    String tenantId,
    String requestedBy,
    String idempotencyKey,
    Map<String, Object> input
) {
}
