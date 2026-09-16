package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;

/**
 * Read-only Designer projection of one immutable Skill version.
 *
 * @param skillId owning Skill ID
 * @param versionId immutable version ID
 * @param version semantic version
 * @param status publication status
 * @param compatible whether the Manifest can round-trip through the current adapter
 * @param compatibilityMessage incompatibility reason when the document is unavailable
 * @param document normalized read-only Designer Document when compatible
 * @param manifest original immutable Manifest, retained even when incompatible
 */
@Schema(title = "Skill Version Designer View")
public record SkillVersionDesignerView(
    String skillId,
    String versionId,
    String version,
    SkillVersionStatus status,
    boolean compatible,
    String compatibilityMessage,
    SkillDesignerDocument document,
    Map<String, Object> manifest
) {
}
