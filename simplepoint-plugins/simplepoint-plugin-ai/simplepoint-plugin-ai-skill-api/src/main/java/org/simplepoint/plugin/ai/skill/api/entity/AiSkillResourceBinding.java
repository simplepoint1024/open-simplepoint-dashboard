package org.simplepoint.plugin.ai.skill.api.entity;

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
 * Immutable MCP Resource or Resource Template pinned by one Skill version.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_resource_bindings",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_resource_binding_version",
            columnList = "skill_version_id"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_resource_binding_scope",
            columnList = "scope_type, tenant_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Resource Binding")
public class AiSkillResourceBinding extends BaseEntityImpl<String> {

  @Column(name = "skill_version_id", length = 64, nullable = false)
  private String skillVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "mcp_server_id", length = 64, nullable = false)
  private String mcpServerId;

  @Column(name = "capability_snapshot_id", length = 64, nullable = false)
  private String capabilitySnapshotId;

  @Column(name = "resource_selector", length = 1024, nullable = false)
  private String resourceSelector;

  @Column(name = "resource_alias", length = 64, nullable = false)
  private String resourceAlias;

  @Column(name = "resource_template", nullable = false)
  private Boolean resourceTemplate;

  @Column(name = "descriptor_hash", length = 64, nullable = false)
  private String descriptorHash;

  @Column(name = "binding_order", nullable = false)
  private int bindingOrder;
}
