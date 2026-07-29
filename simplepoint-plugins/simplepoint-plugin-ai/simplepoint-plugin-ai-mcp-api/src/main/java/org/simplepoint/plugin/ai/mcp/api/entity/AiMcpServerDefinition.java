package org.simplepoint.plugin.ai.mcp.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerDeploymentType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpTransportType;
import org.springframework.core.annotation.Order;

/**
 * Platform or tenant owned remote MCP server registration.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_servers",
    indexes = {
        @Index(name = "idx_simpoint_ai_mcp_server_scope", columnList = "scope_type, tenant_id"),
        @Index(name = "idx_simpoint_ai_mcp_server_status", columnList = "status")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "ai.workbench.mcp-servers.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        icon = Icons.EDIT,
        color = "orange",
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.workbench.mcp-servers.edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        icon = Icons.MINUS_CIRCLE,
        color = "danger",
        danger = true,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        authority = "ai.workbench.mcp-servers.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.mcp-servers.button.discover",
        key = "discover",
        icon = "CloudSyncOutlined",
        color = "blue",
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.workbench.mcp-servers.discover"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.mcp-servers.button.authorize",
        key = "authorize",
        icon = "SafetyCertificateOutlined",
        color = "purple",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.workbench.mcp-servers.authorize"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.mcp-servers.button.deploy",
        key = "deploy",
        icon = "CloudServerOutlined",
        color = "cyan",
        sort = 5,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.workbench.runtime.pools.manage"
    )
})
@Schema(title = "i18n:ai.mcp-servers.entity.title")
public class AiMcpServerDefinition extends BaseEntityImpl<String> {

  @Order(0)
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.scopeType",
      accessMode = Schema.AccessMode.READ_ONLY)
  private AiResourceScope scopeType;

  @Order(1)
  @Column(name = "tenant_id", length = 64)
  @Schema(title = "i18n:ai.mcp-servers.title.tenantId",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String tenantId;

  @Order(2)
  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.name", maxLength = 128)
  private String name;

  @Order(3)
  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.code", maxLength = 128)
  private String code;

  @Order(4)
  @Enumerated(EnumType.STRING)
  @Column(name = "deployment_type", length = 32, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.deploymentType")
  private McpServerDeploymentType deploymentType;

  @Order(5)
  @Enumerated(EnumType.STRING)
  @Column(name = "transport_type", length = 32, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.transportType")
  private McpTransportType transportType;

  @Order(6)
  @Column(name = "endpoint_url", length = 2048)
  @Schema(title = "i18n:ai.mcp-servers.title.endpointUrl", maxLength = 2048)
  private String endpointUrl;

  @Order(7)
  @Enumerated(EnumType.STRING)
  @Column(name = "authentication_type", length = 32, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.authenticationType")
  private McpAuthenticationType authenticationType;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "credential_ciphertext", length = 4096)
  private String credentialCiphertext;

  @Transient
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Schema(title = "i18n:ai.mcp-servers.title.bearerToken",
      accessMode = Schema.AccessMode.WRITE_ONLY)
  private String bearerToken;

  @Transient
  @Schema(title = "i18n:ai.mcp-servers.title.hasCredential",
      accessMode = Schema.AccessMode.READ_ONLY)
  private boolean hasCredential;

  @Order(7)
  @Column(name = "oauth_client_id", length = 2048)
  @Schema(title = "i18n:ai.mcp-servers.title.oauthClientId", maxLength = 2048)
  private String oauthClientId;

  @Transient
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Schema(title = "i18n:ai.mcp-servers.title.oauthClientSecret",
      accessMode = Schema.AccessMode.WRITE_ONLY)
  private String oauthClientSecret;

  @Order(8)
  @Column(name = "oauth_redirect_uri", length = 2048)
  @Schema(title = "i18n:ai.mcp-servers.title.oauthRedirectUri", maxLength = 2048)
  private String oauthRedirectUri;

  @Order(9)
  @Column(name = "oauth_scopes", length = 2048)
  @Schema(title = "i18n:ai.mcp-servers.title.oauthScopes", maxLength = 2048)
  private String oauthScopes;

  @Order(10)
  @Column(name = "oauth_token_endpoint_auth_method", length = 64)
  @Schema(title = "i18n:ai.mcp-servers.title.oauthTokenEndpointAuthMethod",
      maxLength = 64)
  private String oauthTokenEndpointAuthMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "oauth_status", length = 32)
  @Schema(title = "i18n:ai.mcp-servers.title.oauthStatus",
      accessMode = Schema.AccessMode.READ_ONLY)
  private McpOauthStatus oauthStatus;

  @Transient
  @Schema(title = "i18n:ai.mcp-servers.title.hasOauthToken",
      accessMode = Schema.AccessMode.READ_ONLY)
  private boolean hasOauthToken;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_access_token_ciphertext", length = 8192)
  private String oauthAccessTokenCiphertext;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_refresh_token_ciphertext", length = 8192)
  private String oauthRefreshTokenCiphertext;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_resource_uri", length = 2048)
  private String oauthResourceUri;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_authorization_server", length = 2048)
  private String oauthAuthorizationServer;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_authorization_endpoint", length = 2048)
  private String oauthAuthorizationEndpoint;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_token_endpoint", length = 2048)
  private String oauthTokenEndpoint;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_registration_endpoint", length = 2048)
  private String oauthRegistrationEndpoint;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_protected_resource_metadata", columnDefinition = "TEXT")
  private String oauthProtectedResourceMetadata;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "oauth_authorization_server_metadata", columnDefinition = "TEXT")
  private String oauthAuthorizationServerMetadata;

  @Column(name = "oauth_access_token_expires_at")
  @Schema(title = "i18n:ai.mcp-servers.title.oauthAccessTokenExpiresAt",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Instant oauthAccessTokenExpiresAt;

  @Column(name = "oauth_authorized_at")
  @Schema(title = "i18n:ai.mcp-servers.title.oauthAuthorizedAt",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Instant oauthAuthorizedAt;

  @Order(11)
  @Column(name = "allow_private_network", nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.allowPrivateNetwork")
  private Boolean allowPrivateNetwork;

  @Order(12)
  @Column(nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.enabled")
  private Boolean enabled;

  @Order(13)
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(title = "i18n:ai.mcp-servers.title.status",
      accessMode = Schema.AccessMode.READ_ONLY)
  private McpServerStatus status;

  @Column(name = "protocol_version", length = 32)
  @Schema(title = "i18n:ai.mcp-servers.title.protocolVersion",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String protocolVersion;

  @Column(name = "remote_server_name", length = 256)
  @Schema(title = "i18n:ai.mcp-servers.title.remoteServerName",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String remoteServerName;

  @Column(name = "remote_server_version", length = 128)
  @Schema(title = "i18n:ai.mcp-servers.title.remoteServerVersion",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String remoteServerVersion;

  @Column(name = "active_snapshot_id", length = 64)
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private String activeSnapshotId;

  @Column(name = "last_discovered_at")
  @Schema(title = "i18n:ai.mcp-servers.title.lastDiscoveredAt",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Instant lastDiscoveredAt;

  @Column(name = "last_error", length = 1024)
  @Schema(title = "i18n:ai.mcp-servers.title.lastError",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String lastError;

  @Order(14)
  @Column(length = 512)
  @Schema(title = "i18n:ai.mcp-servers.title.description", maxLength = 512)
  private String description;
}
