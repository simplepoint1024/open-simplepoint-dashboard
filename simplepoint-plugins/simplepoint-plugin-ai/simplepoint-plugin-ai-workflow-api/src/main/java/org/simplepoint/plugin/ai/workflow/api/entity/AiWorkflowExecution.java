package org.simplepoint.plugin.ai.workflow.api.entity;

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
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;

/**
 * Durable, version-pinned Agent Workflow execution.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_workflow_executions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_workflow_execution_workflow",
            columnList = "workflow_id, created_at"
        ),
        @Index(
            name = "idx_simpoint_ai_workflow_execution_claim",
            columnList = "status, next_poll_at, lease_expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Workflow Execution")
public class AiWorkflowExecution extends BaseEntityImpl<String> {

  @Column(name = "workflow_id", length = 64, nullable = false)
  private String workflowId;

  @Column(name = "workflow_version_id", length = 64, nullable = false)
  private String workflowVersionId;

  @Column(name = "workflow_version_content_hash", length = 64, nullable = false)
  private String workflowVersionContentHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "requested_by", length = 64)
  private String requestedBy;

  @Column(name = "request_context_id", length = 64)
  private String requestContextId;

  @Column(name = "idempotency_key_hash", length = 64, nullable = false)
  private String idempotencyKeyHash;

  @Column(name = "input_hash", length = 64, nullable = false)
  private String inputHash;

  @JsonIgnore
  @Column(name = "input_json", columnDefinition = "TEXT", nullable = false)
  private String inputJson;

  @JsonIgnore
  @Column(name = "plan_json", columnDefinition = "TEXT", nullable = false)
  private String planJson;

  @JsonIgnore
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private WorkflowExecutionStatus status;

  @Column(name = "maximum_duration_seconds", nullable = false)
  private Integer maximumDurationSeconds;

  @Column(name = "maximum_node_executions", nullable = false)
  private Integer maximumNodeExecutions;

  @Column(name = "maximum_parallelism", nullable = false)
  private Integer maximumParallelism;

  @Column(name = "consumed_node_executions", nullable = false)
  private Integer consumedNodeExecutions;

  @Column(name = "deadline_at", nullable = false)
  private Instant deadlineAt;

  @Column(name = "pause_requested", nullable = false)
  private Boolean pauseRequested;

  @Column(name = "pause_requested_at")
  private Instant pauseRequestedAt;

  @Column(name = "pause_requested_by", length = 64)
  private String pauseRequestedBy;

  @Column(name = "pause_reason", length = 512)
  private String pauseReason;

  @Column(name = "paused_at")
  private Instant pausedAt;

  @Column(name = "resumed_at")
  private Instant resumedAt;

  @Column(name = "resumed_by", length = 64)
  private String resumedBy;

  @Column(name = "next_poll_at")
  private Instant nextPollAt;

  @Column(name = "inactive_since")
  private Instant inactiveSince;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_token", nullable = false)
  private Long leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "attempt_count", nullable = false)
  private Integer attemptCount;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> input;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object output;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiWorkflowNodeExecution> nodes;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiWorkflowHumanTask> humanTasks;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
