package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cooperative pause request for one durable Skill execution.
 *
 * @param reason optional operator reason
 */
@Schema(title = "Skill Execution Pause Request")
public record SkillExecutionPauseRequest(
    @Schema(maxLength = 1024)
    String reason
) {
}
