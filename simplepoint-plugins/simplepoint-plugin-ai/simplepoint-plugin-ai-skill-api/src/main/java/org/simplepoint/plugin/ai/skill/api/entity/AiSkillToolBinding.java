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
 * Immutable Tool capability pinned by one Skill version.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_skill_tool_bindings",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_skill_binding_version",
            columnList = "skill_version_id"
        ),
        @Index(
            name = "idx_simpoint_ai_skill_binding_scope",
            columnList = "scope_type, tenant_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Skill Tool Binding")
public class AiSkillToolBinding extends BaseEntityImpl<String> {

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

  @Column(name = "tool_name", length = 128, nullable = false)
  private String toolName;

  @Column(name = "tool_alias", length = 64, nullable = false)
  private String toolAlias;

  @Column(name = "input_schema_hash", length = 64, nullable = false)
  private String inputSchemaHash;

  @Column(name = "output_schema_hash", length = 64)
  private String outputSchemaHash;

  @Column(name = "binding_order", nullable = false)
  private int bindingOrder;
}
