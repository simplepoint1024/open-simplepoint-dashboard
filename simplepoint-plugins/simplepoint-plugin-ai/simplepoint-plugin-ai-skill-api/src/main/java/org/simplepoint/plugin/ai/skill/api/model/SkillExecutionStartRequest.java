package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Starts one idempotent execution of the active published Skill version.
 *
 * @param idempotencyKey caller-owned key used to deduplicate retries
 * @param input input object validated against the immutable Skill input schema
 */
@Schema(title = "Skill Execution Start Request")
public record SkillExecutionStartRequest(
    String idempotencyKey,
    Map<String, Object> input
) {
}
