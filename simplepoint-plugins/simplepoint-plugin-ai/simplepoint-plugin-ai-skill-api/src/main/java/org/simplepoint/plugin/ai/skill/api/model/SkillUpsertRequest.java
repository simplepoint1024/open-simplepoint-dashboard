package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Mutable metadata accepted when creating or editing a Skill.
 *
 * @param code stable scope-local code; ignored during metadata updates
 * @param name display name
 * @param description optional description
 * @param enabled whether new executions may select the Skill
 */
public record SkillUpsertRequest(
    String code,
    String name,
    String description,
    Boolean enabled
) {
}
