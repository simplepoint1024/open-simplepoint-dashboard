package org.simplepoint.plugin.ai.core.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.core.annotation.Order;

/**
 * Persistent configuration for an AI model provider.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_providers",
    indexes = @Index(
        name = "idx_simpoint_ai_provider_scope",
        columnList = "scope_type, tenant_id"
    ),
    uniqueConstraints = @UniqueConstraint(
        name = "uk_simpoint_ai_provider_scope_code",
        columnNames = {"scope_type", "tenant_id", "code"}
    )
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
        authority = "ai.system.providers.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "ai.providers.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.system.providers.edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.providers.edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true,
        authority = "ai.system.providers.delete"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true,
        authority = "ai.providers.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.providers.button.test",
        key = "test",
        color = "blue",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.system.providers.test"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.providers.button.test",
        key = "test",
        color = "blue",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.providers.test"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.providers.button.discover",
        key = "discover",
        color = "blue",
        icon = "CloudDownloadOutlined",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.system.providers.discover"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.providers.button.discover",
        key = "discover",
        color = "blue",
        icon = "CloudDownloadOutlined",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.providers.discover"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.providers.button.sync",
        key = "sync",
        color = "blue",
        icon = "CloudSyncOutlined",
        sort = 5,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.system.providers.sync"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.providers.button.sync",
        key = "sync",
        color = "blue",
        icon = "CloudSyncOutlined",
        sort = 5,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.providers.sync"
    )
})
@Schema(title = "i18n:ai.providers.entity.title")
public class AiProviderDefinition extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:ai.providers.title.scopeType", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16)
  private AiResourceScope scopeType;

  @Order(1)
  @Schema(title = "i18n:ai.providers.title.tenantId", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Order(2)
  @Schema(title = "i18n:ai.providers.title.name", maxLength = 128, minLength = 1,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 128, nullable = false)
  private String name;

  @Order(3)
  @Schema(title = "i18n:ai.providers.title.code", maxLength = 128, minLength = 1,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 128, nullable = false)
  private String code;

  @Order(4)
  @Schema(title = "i18n:ai.providers.title.providerType",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private AiProviderType providerType;

  @Order(5)
  @Schema(title = "i18n:ai.providers.title.baseUrl", maxLength = 2048,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 2048, nullable = false)
  private String baseUrl;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(length = 4096)
  private String credentialCiphertext;

  @Order(6)
  @Transient
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Schema(title = "i18n:ai.providers.title.apiKey", accessMode = Schema.AccessMode.WRITE_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "widget", value = "password")))
  private String apiKey;

  @Transient
  @Schema(title = "i18n:ai.providers.title.hasApiKey", accessMode = Schema.AccessMode.READ_ONLY)
  private boolean hasApiKey;

  @Order(7)
  @Schema(title = "i18n:ai.providers.title.organizationId", maxLength = 256)
  @Column(length = 256)
  private String organizationId;

  @Order(8)
  @Schema(title = "i18n:ai.providers.title.projectId", maxLength = 256)
  @Column(length = 256)
  private String projectId;

  @Order(9)
  @Schema(title = "i18n:ai.providers.title.apiVersion", maxLength = 64)
  @Column(length = 64)
  private String apiVersion;

  @Order(10)
  @Schema(title = "i18n:ai.providers.title.allowPrivateNetwork",
      description = "i18n:ai.providers.description.allowPrivateNetwork")
  @Column(name = "allow_private_network")
  private Boolean allowPrivateNetwork;

  @Order(11)
  @Schema(title = "i18n:ai.providers.title.enabled",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean enabled;

  @Order(12)
  @Schema(title = "i18n:ai.providers.title.autoSyncEnabled",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean autoSyncEnabled;

  @Order(13)
  @Schema(title = "i18n:ai.providers.title.description", maxLength = 512)
  @Column(length = 512)
  private String description;

  @Schema(title = "i18n:ai.providers.title.lastStatus", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 32)
  private String lastStatus;

  @Schema(title = "i18n:ai.providers.title.lastMessage", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 1024)
  @Column(length = 1024)
  private String lastMessage;

  @Schema(title = "i18n:ai.providers.title.lastTestedAt", accessMode = Schema.AccessMode.READ_ONLY)
  private Instant lastTestedAt;

  @Schema(title = "i18n:ai.providers.title.lastSyncedAt", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Instant lastSyncedAt;
}
