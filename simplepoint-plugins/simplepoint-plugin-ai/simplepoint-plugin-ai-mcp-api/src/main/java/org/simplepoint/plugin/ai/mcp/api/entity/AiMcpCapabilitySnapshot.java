package org.simplepoint.plugin.ai.mcp.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Immutable snapshot of negotiated MCP server capabilities.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_capability_snapshots",
    indexes = {
        @Index(name = "idx_simpoint_ai_mcp_snapshot_server", columnList = "server_id"),
        @Index(name = "idx_simpoint_ai_mcp_snapshot_scope", columnList = "scope_type, tenant_id")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(hidden = true)
public class AiMcpCapabilitySnapshot extends BaseEntityImpl<String> {

  @Column(name = "server_id", length = 64, nullable = false)
  private String serverId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "protocol_version", length = 32, nullable = false)
  private String protocolVersion;

  @Column(name = "remote_server_name", length = 256, nullable = false)
  private String remoteServerName;

  @Column(name = "remote_server_version", length = 128, nullable = false)
  private String remoteServerVersion;

  @Column(name = "capabilities_json", columnDefinition = "TEXT", nullable = false)
  private String capabilitiesJson;

  @Column(name = "tools_json", columnDefinition = "TEXT", nullable = false)
  private String toolsJson;

  @Column(name = "resources_json", columnDefinition = "TEXT")
  private String resourcesJson;

  @Column(name = "resource_templates_json", columnDefinition = "TEXT")
  private String resourceTemplatesJson;

  @Column(name = "prompts_json", columnDefinition = "TEXT")
  private String promptsJson;

  @Column(name = "schema_hash", length = 64, nullable = false)
  private String schemaHash;

  @Column(name = "discovered_at", nullable = false)
  private Instant discoveredAt;
}
