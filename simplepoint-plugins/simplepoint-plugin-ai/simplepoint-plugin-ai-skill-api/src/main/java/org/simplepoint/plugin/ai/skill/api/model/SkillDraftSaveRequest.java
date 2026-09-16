package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;

/**
 * Optimistic save request for one mutable Skill Draft.
 *
 * @param expectedRevision current client revision, or zero when creating
 * @param document complete replacement designer document
 */
@Schema(title = "Skill Draft Save Request")
public record SkillDraftSaveRequest(
    Long expectedRevision,
    SkillDesignerDocument document
) {
}
