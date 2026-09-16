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
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;

/** Append-only event used for restart-safe Skill execution observability. */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_execution_events",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_event_execution",
            columnList = "execution_id, event_sequence"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_event_skill",
            columnList = "skill_id, occurred_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Execution Event")
public class AiSkillExecutionEvent extends BaseEntityImpl<String> {

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "event_sequence", nullable = false)
  private Long sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", length = 48, nullable = false)
  private SkillExecutionEventType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "execution_status", length = 24, nullable = false)
  private SkillExecutionStatus executionStatus;

  @Column(name = "step_id", length = 64)
  private String stepId;

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
