package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Human decision attached to an approval or rejection.
 *
 * @param comment optional decision comment
 */
@Schema(title = "Skill Execution Decision Request")
public record SkillExecutionDecisionRequest(
    @Schema(maxLength = 1024)
    String comment
) {
}
