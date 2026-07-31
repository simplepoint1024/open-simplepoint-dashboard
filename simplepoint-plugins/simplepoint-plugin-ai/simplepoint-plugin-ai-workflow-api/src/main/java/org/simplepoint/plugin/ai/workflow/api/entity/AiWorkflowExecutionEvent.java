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
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;

/**
 * Append-only bounded Workflow execution event.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_workflow_execution_events",
    indexes = @Index(
        name = "idx_simpoint_ai_workflow_event_execution",
        columnList = "execution_id, event_sequence"
    )
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Workflow Execution Event")
public class AiWorkflowExecutionEvent extends BaseEntityImpl<String> {

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Column(name = "event_sequence", nullable = false)
  private Long sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", length = 48, nullable = false)
  private WorkflowExecutionEventType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "execution_status", length = 24, nullable = false)
  private WorkflowExecutionStatus executionStatus;

  @Column(name = "node_execution_id", length = 64)
  private String nodeExecutionId;

  @Column(name = "human_task_id", length = 64)
  private String humanTaskId;

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
