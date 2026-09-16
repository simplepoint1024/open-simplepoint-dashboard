package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optimistic request to restore a historical Draft Revision.
 *
 * @param expectedRevision current client revision
 */
@Schema(title = "Skill Draft Restore Request")
public record SkillDraftRestoreRequest(Long expectedRevision) {
}
