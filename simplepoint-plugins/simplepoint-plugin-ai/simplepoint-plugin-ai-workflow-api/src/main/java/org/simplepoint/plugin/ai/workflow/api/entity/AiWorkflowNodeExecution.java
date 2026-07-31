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
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;

/**
 * Durable checkpoint for one Workflow node and its optional compensation.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_workflow_node_executions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_workflow_node_execution",
            columnList = "execution_id, node_order"
        ),
        @Index(
            name = "idx_simpoint_ai_workflow_node_child",
            columnList = "child_execution_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Workflow Node Execution")
public class AiWorkflowNodeExecution extends BaseEntityImpl<String> {

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Column(name = "node_id", length = 64, nullable = false)
  private String nodeId;

  @Column(name = "node_type", length = 24, nullable = false)
  private String nodeType;

  @Column(name = "node_order", nullable = false)
  private Integer nodeOrder;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private WorkflowNodeExecutionStatus status;

  @Column(name = "attempt_count", nullable = false)
  private Integer attemptCount;

  @JsonIgnore
  @Column(name = "input_json", columnDefinition = "TEXT")
  private String inputJson;

  @JsonIgnore
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Column(name = "output_hash", length = 64)
  private String outputHash;

  @Column(name = "child_type", length = 16)
  private String childType;

  @Column(name = "child_resource_id", length = 64)
  private String childResourceId;

  @Column(name = "child_resource_version_id", length = 64)
  private String childResourceVersionId;

  @Column(name = "child_execution_id", length = 64)
  private String childExecutionId;

  @Column(name = "compensation_skill_id", length = 64)
  private String compensationSkillId;

  @Column(name = "compensation_skill_version_id", length = 64)
  private String compensationSkillVersionId;

  @Column(name = "compensation_content_hash", length = 64)
  private String compensationContentHash;

  @Column(name = "compensation_execution_id", length = 64)
  private String compensationExecutionId;

  @Column(name = "scheduled_at")
  private Instant scheduledAt;

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
}
