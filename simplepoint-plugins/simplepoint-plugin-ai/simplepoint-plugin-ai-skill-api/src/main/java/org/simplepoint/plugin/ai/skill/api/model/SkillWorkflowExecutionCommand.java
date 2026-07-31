package org.simplepoint.plugin.ai.skill.api.model;

import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Internal command used by Workflow Runtime for one pinned Skill version.
 *
 * @param skillId pinned Skill definition
 * @param skillVersionId pinned immutable version
 * @param expectedContentHash content hash captured by the Workflow version
 * @param executionScope Workflow ownership scope
 * @param tenantId Workflow tenant
 * @param requestedBy original Workflow requester
 * @param idempotencyKey stable Workflow node key
 * @param input resolved node input
 */
public record SkillWorkflowExecutionCommand(
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
