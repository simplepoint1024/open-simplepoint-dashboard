package org.simplepoint.plugin.ai.agent.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.agent.api.model.AgentStatus;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Scope-owned Agent identity with mutable display metadata.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_agents",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_agent_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_status",
            columnList = "status"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Agent")
public class AiAgentDefinition extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(length = 64, nullable = false)
  private String code;

  @Column(length = 128, nullable = false)
  private String name;

  @Column(length = 512)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private AgentStatus status;

  @Column(name = "active_version_id", length = 64)
  private String activeVersionId;

  @Column(nullable = false)
  private Boolean enabled;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
