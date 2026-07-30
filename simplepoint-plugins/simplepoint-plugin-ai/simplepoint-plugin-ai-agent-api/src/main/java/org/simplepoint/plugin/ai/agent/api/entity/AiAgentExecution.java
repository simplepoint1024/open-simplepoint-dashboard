package org.simplepoint.plugin.ai.agent.api.entity;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionTimeoutAction;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Durable execution of one immutable Agent version.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_agent_executions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_agent_execution_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_execution_agent",
            columnList = "agent_id, created_at"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_execution_status",
            columnList = "status, next_poll_at, lease_expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Agent Execution")
public class AiAgentExecution extends BaseEntityImpl<String> {

  @Column(name = "agent_id", length = 64, nullable = false)
  private String agentId;

  @Column(name = "agent_version_id", length = 64, nullable = false)
  private String agentVersionId;

  @Column(name = "agent_version_content_hash", length = 64, nullable = false)
  private String agentVersionContentHash;

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
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @JsonIgnore
  @Column(name = "conversation_json", columnDefinition = "TEXT", nullable = false)
  private String conversationJson;

  @JsonIgnore
  @Column(name = "pending_skill_calls_json", columnDefinition = "TEXT")
  private String pendingSkillCallsJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private AgentExecutionStatus status;

  @Column(name = "primary_model_id", length = 64, nullable = false)
  private String primaryModelId;

  @Column(name = "current_model_id", length = 64)
  private String currentModelId;

  @Column(name = "current_skill_binding_id", length = 64)
  private String currentSkillBindingId;

  @Column(name = "current_skill_execution_id", length = 64)
  private String currentSkillExecutionId;

  @Column(name = "current_tool_call_id", length = 128)
  private String currentToolCallId;

  @Column(name = "current_trace_id", length = 64)
  private String currentTraceId;

  @Column(name = "step_count", nullable = false)
  private int stepCount;

  @Column(name = "loop_depth", nullable = false)
  private int loopDepth;

  @Column(name = "maximum_steps", nullable = false)
  private int maximumSteps;

  @Column(name = "maximum_loop_depth", nullable = false)
  private int maximumLoopDepth;

  @Column(name = "maximum_concurrency", nullable = false)
  private int maximumConcurrency;

  @Column(name = "maximum_input_tokens", nullable = false)
  private int maximumInputTokens;

  @Column(name = "maximum_output_tokens", nullable = false)
  private int maximumOutputTokens;

  @Column(name = "maximum_cost", precision = 24, scale = 12, nullable = false)
  private BigDecimal maximumCost;

  @Column(name = "consumed_input_tokens", nullable = false)
  private int consumedInputTokens;

  @Column(name = "consumed_output_tokens", nullable = false)
  private int consumedOutputTokens;

  @Column(name = "consumed_cost", precision = 24, scale = 12, nullable = false)
  private BigDecimal consumedCost;

  @Column(name = "short_term_memory_enabled")
  private Boolean shortTermMemoryEnabled;

  @Column(name = "long_term_memory_enabled")
  private Boolean longTermMemoryEnabled;

  @Column(name = "maximum_memory_messages")
  private Integer maximumMemoryMessages;

  @Column(name = "maximum_memory_summary_characters")
  private Integer maximumMemorySummaryCharacters;

  @Column(name = "compacted_message_count")
  private Integer compactedMessageCount;

  @Column(name = "memory_revision")
  private Integer memoryRevision;

  @Column(name = "memory_summary_hash", length = 64)
  private String memorySummaryHash;

  @Column(name = "last_memory_compacted_at")
  private Instant lastMemoryCompactedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "long_term_memory_scope", length = 16)
  private AgentMemoryScope longTermMemoryScope;

  @Column(name = "maximum_long_term_memory_entries")
  private Integer maximumLongTermMemoryEntries;

  @Column(name = "long_term_memory_retrieval_top_k")
  private Integer longTermMemoryRetrievalTopK;

  @Column(name = "long_term_memory_score_threshold")
  private Double longTermMemoryScoreThreshold;

  @Column(name = "maximum_long_term_memory_injection_characters")
  private Integer maximumLongTermMemoryInjectionCharacters;

  @Column(name = "maximum_long_term_memory_record_characters")
  private Integer maximumLongTermMemoryRecordCharacters;

  @Column(name = "long_term_memory_retention_days")
  private Integer longTermMemoryRetentionDays;

  @JsonIgnore
  @Column(name = "long_term_memory_context_json", columnDefinition = "TEXT")
  private String longTermMemoryContextJson;

  @Column(name = "long_term_memory_retrieved_count")
  private Integer longTermMemoryRetrievedCount;

  @Column(name = "long_term_memory_injected_characters")
  private Integer longTermMemoryInjectedCharacters;

  @Column(name = "long_term_memory_snapshot_hash", length = 64)
  private String longTermMemorySnapshotHash;

  @Column(name = "long_term_memory_retrieved_at")
  private Instant longTermMemoryRetrievedAt;

  @Column(name = "long_term_memory_written_id", length = 64)
  private String longTermMemoryWrittenId;

  @Column(name = "long_term_memory_written_at")
  private Instant longTermMemoryWrittenAt;

  @Column(name = "human_intervention_enabled", nullable = false)
  private Boolean humanInterventionEnabled;

  @Column(name = "maximum_human_interventions", nullable = false)
  private Integer maximumHumanInterventions;

  @Column(name = "human_intervention_timeout_seconds", nullable = false)
  private Integer humanInterventionTimeoutSeconds;

  @Enumerated(EnumType.STRING)
  @Column(name = "human_intervention_timeout_action", length = 16,
      nullable = false)
  private AgentHumanInterventionTimeoutAction humanInterventionTimeoutAction;

  @Column(name = "human_intervention_count", nullable = false)
  private Integer humanInterventionCount;

  @Column(name = "current_human_intervention_id", length = 64)
  private String currentHumanInterventionId;

  @Column(name = "approval_required", nullable = false)
  private Boolean approvalRequired;

  @Column(name = "self_approval_allowed", nullable = false)
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

  @JsonIgnore
  @Column(name = "next_poll_at")
  private Instant nextPollAt;

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
  private List<AiAgentExecutionTrace> traces;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiAgentHumanIntervention> humanInterventions;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
