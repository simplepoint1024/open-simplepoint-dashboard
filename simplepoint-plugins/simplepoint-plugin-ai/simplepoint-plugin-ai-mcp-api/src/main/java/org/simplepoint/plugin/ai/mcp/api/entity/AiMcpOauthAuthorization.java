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
 * One-time OAuth state and encrypted PKCE verifier.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_oauth_authorizations",
    indexes = {
        @Index(name = "idx_simpoint_ai_mcp_oauth_state", columnList = "state_hash"),
        @Index(name = "idx_simpoint_ai_mcp_oauth_server", columnList = "server_id")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(hidden = true)
public class AiMcpOauthAuthorization extends BaseEntityImpl<String> {

  @Column(name = "server_id", length = 64, nullable = false)
  private String serverId;

  @Column(name = "connection_id", length = 64)
  private String connectionId;

  @Column(name = "user_id", length = 64)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "state_hash", length = 64, nullable = false, unique = true)
  private String stateHash;

  @Column(name = "code_verifier_ciphertext", length = 4096, nullable = false)
  private String codeVerifierCiphertext;

  @Column(name = "redirect_uri", length = 2048, nullable = false)
  private String redirectUri;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;
}
