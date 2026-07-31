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
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskStatus;

/**
 * Durable structured human task owned by a Workflow execution.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_workflow_human_tasks",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_workflow_human_execution",
            columnList = "execution_id, status"
        ),
        @Index(
            name = "idx_simpoint_ai_workflow_human_due",
            columnList = "status, due_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Workflow Human Task")
public class AiWorkflowHumanTask extends BaseEntityImpl<String> {

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Column(name = "node_execution_id", length = 64, nullable = false)
  private String nodeExecutionId;

  @Column(name = "node_id", length = 64, nullable = false)
  private String nodeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(length = 256, nullable = false)
  private String title;

  @Column(length = 1024)
  private String description;

  @JsonIgnore
  @Column(name = "input_schema_json", columnDefinition = "TEXT", nullable = false)
  private String inputSchemaJson;

  @JsonIgnore
  @Column(name = "context_json", columnDefinition = "TEXT", nullable = false)
  private String contextJson;

  @JsonIgnore
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Column(name = "output_hash", length = 64)
  private String outputHash;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private WorkflowHumanTaskStatus status;

  @Column(name = "timeout_action", length = 16, nullable = false)
  private String timeoutAction;

  @Column(name = "due_at", nullable = false)
  private Instant dueAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "resolved_by", length = 64)
  private String resolvedBy;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> inputSchema;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object context;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object output;
}
