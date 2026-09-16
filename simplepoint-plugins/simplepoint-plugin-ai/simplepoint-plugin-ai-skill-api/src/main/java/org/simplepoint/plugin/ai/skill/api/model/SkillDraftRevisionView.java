package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Immutable historical Skill Draft snapshot summary.
 *
 * @param id Draft Revision ID
 * @param draftId owning Draft ID
 * @param revision monotonic content revision
 * @param source operation that produced the revision
 * @param validationStatus persisted compilation state
 * @param contentHash canonical Manifest hash when valid
 * @param createdBy author identity
 * @param createdAt creation timestamp
 */
@Schema(title = "Skill Draft Revision")
public record SkillDraftRevisionView(
    String id,
    String draftId,
    long revision,
    SkillDraftRevisionSource source,
    SkillDraftValidationStatus validationStatus,
    String contentHash,
    String createdBy,
    Instant createdAt
) {
}
