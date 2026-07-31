package org.simplepoint.plugin.ai.mcp.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.mcp.api.model.McpTaskStatus;

/**
 * Durable execution backing one task-augmented MCP Tool call.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_tasks",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_mcp_task_owner",
            columnList = "publication_code, subject_hash, client_hash, created_at"
        ),
        @Index(
            name = "idx_simpoint_ai_mcp_task_claim",
            columnList = "status, next_attempt_at, lease_expires_at"
        ),
        @Index(
            name = "idx_simpoint_ai_mcp_task_expiry",
            columnList = "expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class AiMcpTask extends BaseEntityImpl<String> {

  @Column(name = "publication_code", length = 128, nullable = false)
  private String publicationCode;

  @Column(name = "tool_name", length = 256, nullable = false)
  private String toolName;

  @Column(name = "subject_id", length = 512, nullable = false)
  private String subject;

  @Column(name = "subject_hash", length = 64, nullable = false)
  private String subjectHash;

  @Column(name = "client_id", length = 512, nullable = false)
  private String clientId;

  @Column(name = "client_hash", length = 64, nullable = false)
  private String clientHash;

  @Column(name = "source_session_id", length = 512)
  private String sourceSessionId;

  @Column(name = "operation_id", length = 64, nullable = false)
  private String operationId;

  @JsonIgnore
  @Column(name = "arguments_json", columnDefinition = "TEXT", nullable = false)
  private String argumentsJson;

  @JsonIgnore
  @Column(name = "result_json", columnDefinition = "TEXT")
  private String resultJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private McpTaskStatus status;

  @Column(name = "status_message", length = 1024)
  private String statusMessage;

  @Column(name = "ttl_millis", nullable = false)
  private Long ttlMillis;

  @Column(name = "poll_interval_millis", nullable = false)
  private Long pollIntervalMillis;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_token", nullable = false)
  private Long leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "attempt_count", nullable = false)
  private Integer attemptCount;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Version
  @Column(name = "lock_version", nullable = false)
  private Long lockVersion;
}
