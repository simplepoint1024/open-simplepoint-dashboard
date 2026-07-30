package org.simplepoint.plugin.ai.agent.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Immutable published Skill version pinned by one Agent version.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_agent_skill_bindings",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_agent_skill_binding_version",
            columnList = "agent_version_id"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_skill_binding_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_agent_skill_binding_skill",
            columnList = "skill_id, skill_version_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Agent Skill Binding")
public class AiAgentSkillBinding extends BaseEntityImpl<String> {

  @Column(name = "agent_version_id", length = 64, nullable = false)
  private String agentVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "skill_id", length = 64, nullable = false)
  private String skillId;

  @Column(name = "skill_version_id", length = 64, nullable = false)
  private String skillVersionId;

  @Column(name = "skill_alias", length = 64, nullable = false)
  private String skillAlias;

  @Column(name = "skill_code", length = 64, nullable = false)
  private String skillCode;

  @Column(name = "skill_version_name", length = 64, nullable = false)
  private String skillVersionName;

  @Column(name = "skill_content_hash", length = 64, nullable = false)
  private String skillContentHash;

  @Column(name = "binding_order", nullable = false)
  private int bindingOrder;
}
