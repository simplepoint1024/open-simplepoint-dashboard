package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Starts a debug execution pinned to one immutable Skill Draft Revision.
 *
 * @param revision immutable Draft Revision to execute
 * @param mode debug execution mode
 * @param testCaseId required immutable Designer test case for MOCK mode
 * @param idempotencyKey caller-owned key used to deduplicate retries
 * @param input input object validated against the pinned Draft schema
 */
@Schema(title = "Skill Draft Debug Execution Start Request")
public record SkillDraftDebugExecutionStartRequest(
    Long revision,
    SkillDebugMode mode,
    String testCaseId,
    String idempotencyKey,
    Map<String, Object> input
) {
}
