package org.simplepoint.plugin.ai.skill.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionApprovalPolicy;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBudget;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;

/**
 * Immutable declarative Skill Artifact content and mutable publication state.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_versions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_version_skill",
            columnList = "skill_id"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_version_scope",
            columnList = "scope_type, tenant_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Version")
public class AiSkillVersion extends BaseEntityImpl<String> {

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "version_name", length = 64, nullable = false)
  private String version;

  @Column(name = "artifact_reference", length = 512, nullable = false)
  private String artifactReference;

  @Column(name = "artifact_digest", length = 71, nullable = false)
  private String artifactDigest;

  @Column(name = "artifact_media_type", length = 128, nullable = false)
  private String artifactMediaType;

  @Column(name = "artifact_config_digest", length = 71, nullable = false)
  private String artifactConfigDigest;

  @Column(name = "artifact_content_digest", length = 71, nullable = false)
  private String artifactContentDigest;

  @Column(name = "artifact_signature_required", nullable = false)
  private Boolean artifactSignatureRequired;

  @Column(name = "artifact_signature_verified", nullable = false)
  private Boolean artifactSignatureVerified;

  @Column(
      name = "artifact_verification_policy_hash",
      length = 71,
      nullable = false
  )
  private String artifactVerificationPolicyHash;

  @Column(name = "artifact_verified_at", nullable = false)
  private Instant artifactVerifiedAt;

  @Column(name = "manifest_schema_version", length = 32, nullable = false)
  private String manifestSchemaVersion;

  @Column(name = "content_hash", length = 64, nullable = false)
  private String contentHash;

  @JsonIgnore
  @Column(name = "manifest_json", columnDefinition = "TEXT", nullable = false)
  private String manifestJson;

  @JsonIgnore
  @Column(name = "input_schema_json", columnDefinition = "TEXT", nullable = false)
  private String inputSchemaJson;

  @JsonIgnore
  @Column(name = "output_schema_json", columnDefinition = "TEXT", nullable = false)
  private String outputSchemaJson;

  @JsonIgnore
  @Column(name = "workflow_json", columnDefinition = "TEXT", nullable = false)
  private String workflowJson;

  @JsonIgnore
  @Column(name = "budget_json", columnDefinition = "TEXT")
  private String budgetJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private SkillVersionStatus status;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "deprecated_at")
  private Instant deprecatedAt;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> manifest;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> inputSchema;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> outputSchema;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> workflow;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private SkillExecutionBudget budget;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private SkillExecutionApprovalPolicy approvalPolicy;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiSkillToolBinding> toolBindings;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiSkillPromptBinding> promptBindings;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiSkillResourceBinding> resourceBindings;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
