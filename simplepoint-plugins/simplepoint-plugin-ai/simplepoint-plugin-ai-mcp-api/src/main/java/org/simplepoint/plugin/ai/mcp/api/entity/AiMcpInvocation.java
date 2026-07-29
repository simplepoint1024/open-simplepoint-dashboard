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
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilityType;
import org.simplepoint.plugin.ai.mcp.api.model.McpInvocationStatus;

/**
 * Metadata-only MCP capability invocation ledger.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_tool_invocations",
    indexes = {
        @Index(name = "idx_simpoint_ai_mcp_invocation_scope",
            columnList = "scope_type, tenant_id"),
        @Index(name = "idx_simpoint_ai_mcp_invocation_server", columnList = "server_id"),
        @Index(name = "idx_simpoint_ai_mcp_invocation_started", columnList = "started_at"),
        @Index(name = "idx_simpoint_ai_mcp_invocation_capability",
            columnList = "capability_type, capability_name")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(hidden = true)
public class AiMcpInvocation extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "user_id", length = 64)
  private String userId;

  @Column(name = "context_id", length = 64)
  private String contextId;

  @Column(name = "client_id", length = 2048)
  private String clientId;

  @Column(name = "trace_id", length = 64, nullable = false)
  private String traceId;

  @Column(name = "server_id", length = 64, nullable = false)
  private String serverId;

  @Column(name = "snapshot_id", length = 64, nullable = false)
  private String snapshotId;

  @Enumerated(EnumType.STRING)
  @Column(name = "capability_type", length = 16)
  private McpCapabilityType capabilityType;

  @Column(name = "capability_name", length = 4096)
  private String capabilityName;

  @Column(name = "request_hash", length = 64)
  private String requestHash;

  @Column(name = "result_hash", length = 64)
  private String resultHash;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private McpInvocationStatus status;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "duration_millis")
  private Long durationMillis;

  @Column(name = "error_code", length = 128)
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;
}
