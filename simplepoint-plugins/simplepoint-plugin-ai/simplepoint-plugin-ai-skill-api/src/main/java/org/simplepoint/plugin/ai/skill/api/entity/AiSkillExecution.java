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
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;

/**
 * Durable, scope-owned execution of one immutable Skill version.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_executions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_execution_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_execution_skill",
            columnList = "skill_id, created_at"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_execution_status",
            columnList = "status, lease_expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Execution")
public class AiSkillExecution extends BaseEntityImpl<String> {

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Column(name = "skill_version_id", length = 64, nullable = false)
  private String skillVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "idempotency_key_hash", length = 64, nullable = false)
  private String idempotencyKeyHash;

  @Column(name = "input_hash", length = 64, nullable = false)
  private String inputHash;

  @JsonIgnore
  @Column(name = "input_json", columnDefinition = "TEXT", nullable = false)
  private String inputJson;

  @JsonIgnore
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @JsonIgnore
  @Column(name = "output_template_json", columnDefinition = "TEXT")
  private String outputTemplateJson;

  @JsonIgnore
  @Column(name = "output_schema_json", columnDefinition = "TEXT", nullable = false)
  private String outputSchemaJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private SkillExecutionStatus status;

  @Column(name = "current_step_id", length = 64)
  private String currentStepId;

  @Column(name = "requested_by", length = 64)
  private String requestedBy;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @JsonIgnore
  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @JsonIgnore
  @Column(name = "lease_token", nullable = false)
  private long leaseToken;

  @JsonIgnore
  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> input;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object output;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiSkillExecutionStep> steps;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
