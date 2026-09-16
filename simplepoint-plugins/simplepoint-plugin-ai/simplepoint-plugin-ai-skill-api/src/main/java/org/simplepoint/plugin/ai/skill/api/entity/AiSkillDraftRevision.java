package org.simplepoint.plugin.ai.skill.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRevisionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;

/** Immutable historical snapshot of one Skill Draft save. */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_draft_revisions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_draft_revision_draft",
            columnList = "draft_id, revision"
        )
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_simpoint_ai_skill_draft_revision",
            columnNames = {"draft_id", "revision"}
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Draft Revision Persistence")
public class AiSkillDraftRevision extends BaseEntityImpl<String> {

  @Column(name = "draft_id", length = 64, nullable = false)
  private String draftId;

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Column(nullable = false)
  private Long revision;

  @Enumerated(EnumType.STRING)
  @Column(name = "revision_source", length = 24, nullable = false)
  private SkillDraftRevisionSource revisionSource;

  @JsonIgnore
  @Column(name = "designer_json", columnDefinition = "TEXT", nullable = false)
  private String designerJson;

  @JsonIgnore
  @Column(name = "compiled_manifest_json", columnDefinition = "TEXT")
  private String compiledManifestJson;

  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "validation_status", length = 16, nullable = false)
  private SkillDraftValidationStatus validationStatus;

  @JsonIgnore
  @Column(
      name = "validation_result_json",
      columnDefinition = "TEXT",
      nullable = false
  )
  private String validationResultJson;
}
