package org.simplepoint.plugin.ai.skill.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;

/** Mutable designer state for one Skill. */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_drafts",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_draft_scope",
            columnList = "scope_type, tenant_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Draft Persistence")
public class AiSkillDraft extends BaseEntityImpl<String> {

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(nullable = false)
  private Long revision;

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

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
