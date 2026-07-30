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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;

/**
 * One model or Skill operation in an Agent execution trace.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_agent_execution_traces",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_agent_trace_execution",
            columnList = "execution_id, trace_sequence"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_trace_skill_execution",
            columnList = "skill_execution_id"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_trace_invocation",
            columnList = "model_invocation_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Agent Execution Trace")
public class AiAgentExecutionTrace extends BaseEntityImpl<String> {

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Column(name = "trace_sequence", nullable = false)
  private int sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "trace_type", length = 16, nullable = false)
  private AgentTraceType type;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private AgentTraceStatus status;

  @Column(name = "model_definition_id", length = 64)
  private String modelDefinitionId;

  @Column(name = "model_invocation_id", length = 64)
  private String modelInvocationId;

  @Column(name = "skill_binding_id", length = 64)
  private String skillBindingId;

  @Column(name = "skill_id", length = 64)
  private String skillId;

  @Column(name = "skill_version_id", length = 64)
  private String skillVersionId;

  @Column(name = "skill_execution_id", length = 64)
  private String skillExecutionId;

  @Column(name = "capability_alias", length = 64)
  private String capabilityAlias;

  @Column(name = "tool_call_id", length = 128)
  private String toolCallId;

  @Column(name = "request_hash", length = 64, nullable = false)
  private String requestHash;

  @Column(name = "response_hash", length = 64)
  private String responseHash;

  @JsonIgnore
  @Column(name = "input_json", columnDefinition = "TEXT")
  private String inputJson;

  @JsonIgnore
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Column(name = "input_tokens")
  private Integer inputTokens;

  @Column(name = "output_tokens")
  private Integer outputTokens;

  @Column(name = "cost", precision = 24, scale = 12)
  private BigDecimal cost;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object input;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object output;
}
