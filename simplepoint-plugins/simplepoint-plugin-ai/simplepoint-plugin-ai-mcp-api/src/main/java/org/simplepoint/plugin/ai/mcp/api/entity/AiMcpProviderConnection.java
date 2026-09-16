package org.simplepoint.plugin.ai.mcp.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
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
import org.simplepoint.plugin.ai.mcp.api.model.AiMcpOauthErrorCodeSerializer;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthStatus;

/** Subject-owned OAuth credential used by one MCP provider connection. */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_provider_connections",
    indexes = {
        @Index(name = "idx_simpoint_ai_mcp_connection_server",
            columnList = "server_id"),
        @Index(name = "idx_simpoint_ai_mcp_connection_subject",
            columnList = "tenant_id, user_id")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "MCP Provider OAuth Connection")
public class AiMcpProviderConnection extends BaseEntityImpl<String> {

  @Column(name = "server_id", length = 64, nullable = false)
  private String serverId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "user_id", length = 64, nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private McpOauthStatus status;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "access_token_ciphertext", length = 8192)
  private String accessTokenCiphertext;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "refresh_token_ciphertext", length = 8192)
  private String refreshTokenCiphertext;

  @Column(name = "granted_scopes", length = 2048)
  private String grantedScopes;

  @Column(name = "access_token_expires_at")
  private Instant accessTokenExpiresAt;

  @Column(name = "authorized_at")
  private Instant authorizedAt;

  @Column(name = "token_version", nullable = false)
  private Long tokenVersion;

  @Column(name = "last_error", length = 1024)
  @JsonSerialize(using = AiMcpOauthErrorCodeSerializer.class)
  private String lastError;

  /** Returns only whether token material exists; ciphertext is never serialized. */
  public boolean isConnected() {
    return status == McpOauthStatus.CONNECTED
        && accessTokenCiphertext != null && !accessTokenCiphertext.isBlank();
  }
}
