package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;

/**
 * Complete mutable Skill Draft returned to the designer.
 *
 * @param id Draft ID
 * @param skillId owning Skill ID
 * @param scopeType inherited Skill scope
 * @param tenantId inherited tenant ID
 * @param revision monotonic content revision
 * @param document current designer document
 * @param compilation current compilation result
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
@Schema(title = "Skill Draft")
public record SkillDraftView(
    String id,
    String skillId,
    AiResourceScope scopeType,
    String tenantId,
    long revision,
    SkillDesignerDocument document,
    SkillDesignerCompilationResult compilation,
    Instant createdAt,
    Instant updatedAt
) {
}
