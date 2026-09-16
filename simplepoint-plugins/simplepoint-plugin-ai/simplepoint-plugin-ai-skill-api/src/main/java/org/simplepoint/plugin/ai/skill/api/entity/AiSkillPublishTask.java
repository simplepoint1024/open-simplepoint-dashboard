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
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStatus;

/** Durable, restart-safe one-click Skill publication state. */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_publish_tasks",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_publish_task_skill",
            columnList = "skill_id, created_at"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_publish_task_due",
            columnList = "status, next_attempt_at, lease_expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Publish Task")
public class AiSkillPublishTask extends BaseEntityImpl<String> {

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Column(name = "draft_id", length = 64, nullable = false)
  private String draftId;

  @Column(name = "draft_revision", nullable = false)
  private Long draftRevision;

  @Column(name = "draft_content_hash", length = 64, nullable = false)
  private String draftContentHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "requested_by", length = 64)
  private String requestedBy;

  @Column(name = "version_name", length = 64, nullable = false)
  private String version;

  @Column(name = "activate_version", nullable = false)
  private Boolean activate;

  @Column(name = "idempotency_key_hash", length = 64, nullable = false)
  private String idempotencyKeyHash;

  @JsonIgnore
  @Column(name = "manifest_json", columnDefinition = "TEXT", nullable = false)
  private String manifestJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private SkillPublishTaskStatus status;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private SkillPublishTaskStage stage;

  @Column(name = "attempt_count", nullable = false)
  private Integer attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_token", nullable = false)
  private Long leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "artifact_reference", length = 512)
  private String artifactReference;

  @Column(name = "artifact_digest", length = 71)
  private String artifactDigest;

  @Column(name = "artifact_config_digest", length = 71)
  private String artifactConfigDigest;

  @Column(name = "artifact_content_digest", length = 71)
  private String artifactContentDigest;

  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Column(name = "skill_version_id", length = 64)
  private String skillVersionId;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", length = 2048)
  private String errorMessage;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
