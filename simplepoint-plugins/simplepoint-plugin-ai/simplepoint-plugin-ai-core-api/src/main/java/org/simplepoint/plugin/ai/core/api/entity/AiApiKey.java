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
import org.springframework.core.annotation.Order;

/** A client credential issued by SimplePoint for invoking the model compatibility gateway. */
@Data
@Entity
@Table(name = "simpoint_ai_api_keys", indexes = {
    @Index(name = "idx_simpoint_ai_api_key_prefix", columnList = "key_prefix", unique = true),
    @Index(name = "idx_simpoint_ai_api_key_scope", columnList = "scope_type, tenant_id")
})
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ButtonDeclarations({
    @ButtonDeclaration(title = PublicButtonKeys.ADD_TITLE, key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE, sort = 0, argumentMinSize = 0, argumentMaxSize = 1,
        authority = "ai.system.api-keys.create"),
    @ButtonDeclaration(title = PublicButtonKeys.ADD_TITLE, key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE, sort = 0, argumentMinSize = 0, argumentMaxSize = 1,
        authority = "ai.api-keys.create"),
    @ButtonDeclaration(title = PublicButtonKeys.EDIT_TITLE, key = PublicButtonKeys.EDIT_KEY,
        icon = Icons.EDIT, color = "orange", sort = 1, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "ai.system.api-keys.edit"),
    @ButtonDeclaration(title = PublicButtonKeys.EDIT_TITLE, key = PublicButtonKeys.EDIT_KEY,
        icon = Icons.EDIT, color = "orange", sort = 1, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "ai.api-keys.edit"),
    @ButtonDeclaration(title = "i18n:ai.api-keys.button.rotate", key = "rotate",
        icon = "SyncOutlined", color = "orange", sort = 2, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "ai.system.api-keys.rotate"),
    @ButtonDeclaration(title = "i18n:ai.api-keys.button.rotate", key = "rotate",
        icon = "SyncOutlined", color = "orange", sort = 2, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "ai.api-keys.rotate"),
    @ButtonDeclaration(title = PublicButtonKeys.DELETE_TITLE, key = PublicButtonKeys.DELETE_KEY,
        icon = Icons.MINUS_CIRCLE, color = "danger", danger = true, sort = 3,
        argumentMinSize = 1, argumentMaxSize = 10, authority = "ai.system.api-keys.delete"),
    @ButtonDeclaration(title = PublicButtonKeys.DELETE_TITLE, key = PublicButtonKeys.DELETE_KEY,
        icon = Icons.MINUS_CIRCLE, color = "danger", danger = true, sort = 3,
        argumentMinSize = 1, argumentMaxSize = 10, authority = "ai.api-keys.delete")
})
@Schema(title = "i18n:ai.api-keys.entity.title")
public class AiApiKey extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:ai.api-keys.title.scopeType", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Schema(title = "i18n:ai.api-keys.title.tenantId", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Order(1)
  @Schema(title = "i18n:ai.api-keys.title.name", minLength = 1, maxLength = 128,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 128, nullable = false)
  private String name;

  @Order(2)
  @Schema(title = "i18n:ai.api-keys.title.keyPrefix", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "key_prefix", length = 40, nullable = false, unique = true)
  private String keyPrefix;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "secret_hash", length = 128, nullable = false)
  private String secretHash;

  @Order(3)
  @Schema(title = "i18n:ai.api-keys.title.enabled",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(nullable = false)
  private Boolean enabled;

  @Order(4)
  @Schema(title = "i18n:ai.api-keys.title.rateLimitPerMinute",
      minimum = "1", maximum = "100000",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "rate_limit_per_minute")
  private Integer rateLimitPerMinute;

  @Order(5)
  @Schema(title = "i18n:ai.api-keys.title.expiresAt",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "expires_at")
  private Instant expiresAt;

  @Order(6)
  @Schema(title = "i18n:ai.api-keys.title.lastUsedAt", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Schema(title = "i18n:ai.api-keys.title.usageCount", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "usage_count", nullable = false)
  private Long usageCount;

  @Order(7)
  @Schema(title = "i18n:ai.api-keys.title.description", maxLength = 512)
  @Column(length = 512)
  private String description;

  @Column(name = "revoked_at")
  @Schema(hidden = true)
  private Instant revokedAt;

  @Transient
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @Schema(hidden = true)
  private String issuedKey;
}
