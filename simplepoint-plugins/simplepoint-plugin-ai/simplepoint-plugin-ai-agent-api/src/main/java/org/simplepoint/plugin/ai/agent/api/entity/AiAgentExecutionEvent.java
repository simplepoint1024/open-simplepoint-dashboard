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
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Append-only execution event used for incremental observability.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_agent_execution_events",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_agent_event_execution",
            columnList = "execution_id, event_sequence"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_event_agent",
            columnList = "agent_id, occurred_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Agent Execution Event")
public class AiAgentExecutionEvent extends BaseEntityImpl<String> {

  @Column(name = "agent_id", length = 64, nullable = false)
  private String agentId;

  @Column(name = "agent_version_id", length = 64, nullable = false)
  private String agentVersionId;

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "event_sequence", nullable = false)
  private int sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", length = 48, nullable = false)
  private AgentExecutionEventType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "execution_status", length = 24, nullable = false)
  private AgentExecutionStatus executionStatus;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "intervention_id", length = 64)
  private String interventionId;

  @Column(name = "actor_id", length = 64)
  private String actorId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @JsonIgnore
  @Column(name = "payload_json", columnDefinition = "TEXT")
  private String payloadJson;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> payload;
}
