package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Structured Skill Draft API error.
 *
 * @param code stable machine-readable error code
 * @param message human-readable explanation
 * @param expectedRevision client revision when relevant
 * @param currentRevision current server revision when relevant
 */
@Schema(title = "Skill Draft Error")
public record SkillDraftError(
    String code,
    String message,
    Long expectedRevision,
    Long currentRevision
) {
}
