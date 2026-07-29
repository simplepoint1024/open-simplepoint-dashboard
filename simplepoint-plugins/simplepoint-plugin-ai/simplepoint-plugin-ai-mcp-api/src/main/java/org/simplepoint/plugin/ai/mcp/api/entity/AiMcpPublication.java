package org.simplepoint.plugin.ai.mcp.api.entity;

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
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.model.McpPublicationStatus;
import org.springframework.core.annotation.Order;

/**
 * Platform or tenant MCP endpoint exposed through the independent Gateway.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_mcp_publications",
    indexes = {
        @Index(name = "idx_simpoint_ai_mcp_publication_scope",
            columnList = "scope_type, tenant_id"),
        @Index(name = "idx_simpoint_ai_mcp_publication_code", columnList = "code")
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
        authority = "ai.workbench.mcp-publications.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        icon = Icons.EDIT,
        color = "orange",
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.workbench.mcp-publications.edit"
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
        authority = "ai.workbench.mcp-publications.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.mcp-publications.button.manifest",
        key = "manifest",
        icon = "FileSearchOutlined",
        color = "blue",
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.workbench.mcp-publications.view"
    )
})
@Schema(title = "i18n:ai.mcp-publications.entity.title")
public class AiMcpPublication extends BaseEntityImpl<String> {

  @Order(0)
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.scopeType",
      accessMode = Schema.AccessMode.READ_ONLY)
  private AiResourceScope scopeType;

  @Order(1)
  @Column(name = "tenant_id", length = 64)
  @Schema(title = "i18n:ai.mcp-publications.title.tenantId",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String tenantId;

  @Order(2)
  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.name", maxLength = 128)
  private String name;

  @Order(3)
  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.code", maxLength = 128)
  private String code;

  @Order(4)
  @Column(name = "upstream_server_id", length = 64, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.upstreamServerId")
  private String upstreamServerId;

  @Order(5)
  @Column(name = "canonical_resource_uri", length = 2048, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.canonicalResourceUri",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String canonicalResourceUri;

  @Order(6)
  @Column(name = "authorization_server_uri", length = 2048, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.authorizationServerUri",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String authorizationServerUri;

  @Order(7)
  @Column(name = "required_scopes", length = 2048, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.requiredScopes")
  private String requiredScopes;

  @Order(8)
  @Column(name = "rate_limit_per_minute", nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.rateLimitPerMinute")
  private Integer rateLimitPerMinute;

  @Order(9)
  @Column(nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.enabled")
  private Boolean enabled;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(title = "i18n:ai.mcp-publications.title.status",
      accessMode = Schema.AccessMode.READ_ONLY)
  private McpPublicationStatus status;

  @Order(10)
  @Column(length = 512)
  @Schema(title = "i18n:ai.mcp-publications.title.description", maxLength = 512)
  private String description;
}
