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
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;

/**
 * Durable checkpoint for one Skill workflow step.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_execution_steps",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_execution_step_execution",
            columnList = "execution_id, step_order"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Execution Step")
public class AiSkillExecutionStep extends BaseEntityImpl<String> {

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Column(name = "step_id", length = 64, nullable = false)
  private String stepId;

  @Column(name = "step_type", length = 32, nullable = false)
  private String stepType;

  @Column(name = "step_order", nullable = false)
  private int stepOrder;

  @Column(name = "tool_binding_id", length = 64)
  private String toolBindingId;

  @Column(name = "tool_alias", length = 64)
  private String toolAlias;

  @Column(name = "mcp_server_id", length = 64)
  private String mcpServerId;

  @Column(name = "capability_snapshot_id", length = 64)
  private String capabilitySnapshotId;

  @Column(name = "tool_name", length = 128)
  private String toolName;

  @Column(name = "input_schema_hash", length = 64)
  private String inputSchemaHash;

  @JsonIgnore
  @Column(name = "arguments_template_json", columnDefinition = "TEXT")
  private String argumentsTemplateJson;

  @JsonIgnore
  @Column(name = "input_json", columnDefinition = "TEXT")
  private String inputJson;

  @JsonIgnore
  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private SkillExecutionStepStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> input;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Object output;
}
