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
import org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillTestAssertionResult;

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
        ),
        @Index(
            name = "idx_simpoint_ai_skill_execution_test_run",
            columnList = "test_run_id, test_run_order"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Execution")
public class AiSkillExecution extends BaseEntityImpl<String> {

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Column(name = "skill_version_id", length = 64)
  private String skillVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 16, nullable = false)
  private SkillExecutionSource sourceType;

  @Column(name = "draft_id", length = 64)
  private String draftId;

  @Column(name = "draft_revision")
  private Long draftRevision;

  @Column(name = "draft_content_hash", length = 64)
  private String draftContentHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "debug_mode", length = 16)
  private SkillDebugMode debugMode;

  @Column(name = "test_case_id", length = 64)
  private String testCaseId;

  @Column(name = "mock_config_hash", length = 64)
  private String mockConfigHash;

  @JsonIgnore
  @Column(name = "mock_config_json", columnDefinition = "TEXT")
  private String mockConfigJson;

  @Column(name = "assertions_passed")
  private Boolean assertionsPassed;

  @JsonIgnore
  @Column(name = "assertion_results_json", columnDefinition = "TEXT")
  private String assertionResultsJson;

  @Column(name = "test_run_id", length = 64)
  private String testRunId;

  @Column(name = "test_run_order")
  private Integer testRunOrder;

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
  @Column(name = "workflow_plan_json", columnDefinition = "TEXT")
  private String workflowPlanJson;

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

  @Column(name = "approval_required")
  private Boolean approvalRequired;

  @Column(name = "self_approval_allowed")
  private Boolean selfApprovalAllowed;

  @Column(name = "approval_instructions", length = 512)
  private String approvalInstructions;

  @Column(name = "approval_requested_at")
  private Instant approvalRequestedAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "approved_by", length = 64)
  private String approvedBy;

  @Column(name = "approval_comment", length = 1024)
  private String approvalComment;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "rejected_by", length = 64)
  private String rejectedBy;

  @Column(name = "rejection_reason", length = 1024)
  private String rejectionReason;

  @Column(name = "pause_requested")
  private Boolean pauseRequested;

  @Column(name = "pause_requested_at")
  private Instant pauseRequestedAt;

  @Column(name = "pause_requested_by", length = 64)
  private String pauseRequestedBy;

  @Column(name = "pause_reason", length = 1024)
  private String pauseReason;

  @Column(name = "paused_at")
  private Instant pausedAt;

  @Column(name = "resumed_at")
  private Instant resumedAt;

  @Column(name = "resumed_by", length = 64)
  private String resumedBy;

  @Column(name = "cancel_requested", nullable = false)
  private Boolean cancelRequested = false;

  @Column(name = "cancel_requested_at")
  private Instant cancelRequestedAt;

  @Column(name = "cancel_requested_by", length = 64)
  private String cancelRequestedBy;

  @Column(name = "cancel_reason", length = 1024)
  private String cancelReason;

  @JsonIgnore
  @Column(name = "breakpoint_step_ids_json", columnDefinition = "TEXT")
  private String breakpointStepIdsJson;

  @JsonIgnore
  @Column(name = "breakpoint_bypassed_step_id", length = 64)
  private String breakpointBypassedStepId;

  @JsonIgnore
  @Column(name = "inactive_since")
  private Instant inactiveSince;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "maximum_tool_calls")
  private Integer maximumToolCalls;

  @Column(name = "maximum_duration_seconds")
  private Integer maximumDurationSeconds;

  @Column(name = "maximum_payload_bytes")
  private Long maximumPayloadBytes;

  @Column(name = "consumed_tool_calls")
  private Integer consumedToolCalls;

  @Column(name = "consumed_payload_bytes")
  private Long consumedPayloadBytes;

  @Column(name = "deadline_at")
  private Instant deadlineAt;

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
  private List<SkillTestAssertionResult> assertionResults;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<String> breakpointStepIds;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiSkillExecutionStep> steps;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
