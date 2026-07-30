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
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionOutcome;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionStatus;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Durable operator task attached to one Agent execution.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_agent_human_interventions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_agent_intervention_execution",
            columnList = "execution_id, requested_at"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_intervention_status",
            columnList = "status, expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Agent Human Intervention")
public class AiAgentHumanIntervention extends BaseEntityImpl<String> {

  @Column(name = "agent_id", length = 64, nullable = false)
  private String agentId;

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private AgentHumanInterventionStatus status;

  @Column(length = 1024, nullable = false)
  private String prompt;

  @Column(name = "requested_by", length = 64, nullable = false)
  private String requestedBy;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "waiting_at")
  private Instant waitingAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private AgentHumanInterventionOutcome outcome;

  @JsonIgnore
  @Column(name = "response_json", columnDefinition = "TEXT")
  private String responseJson;

  @Column(name = "response_comment", length = 1024)
  private String responseComment;

  @Column(name = "responded_by", length = 64)
  private String respondedBy;

  @Column(name = "responded_at")
  private Instant respondedAt;

  @Column(name = "completion_reason", length = 1024)
  private String completionReason;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> response;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
