package org.simplepoint.plugin.ai.core.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.core.annotation.Order;

/**
 * A model made available through a configured AI provider.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_models",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_simpoint_ai_model_provider_model",
        columnNames = {"provider_id", "model_id"}
    ),
    indexes = {
        @Index(name = "idx_simpoint_ai_model_provider", columnList = "provider_id"),
        @Index(name = "idx_simpoint_ai_model_scope", columnList = "scope_type, tenant_id")
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
        authority = "ai.system.models.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "ai.models.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.system.models.edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.models.edit"
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
        authority = "ai.system.models.delete"
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
        authority = "ai.models.delete"
    )
})
@Schema(title = "i18n:ai.models.entity.title")
public class AiModelDefinition extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:ai.models.title.scopeType", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16)
  private AiResourceScope scopeType;

  @Order(1)
  @Schema(title = "i18n:ai.models.title.tenantId", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Order(2)
  @Schema(title = "i18n:ai.models.title.providerId",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "provider_id", length = 64, nullable = false)
  private String providerId;

  @Transient
  @Schema(title = "i18n:ai.models.title.providerName", accessMode = Schema.AccessMode.READ_ONLY)
  private String providerName;

  @Order(3)
  @Schema(title = "i18n:ai.models.title.modelId", maxLength = 256, minLength = 1,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "model_id", length = 256, nullable = false)
  private String modelId;

  @Order(4)
  @Schema(title = "i18n:ai.models.title.displayName", maxLength = 256,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 256)
  private String displayName;

  @Order(5)
  @Schema(title = "i18n:ai.models.title.modelType",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private AiModelType modelType;

  @Order(6)
  @Schema(title = "i18n:ai.models.title.enabled",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean enabled;

  @Schema(title = "i18n:ai.models.title.available", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean available;

  @Schema(title = "i18n:ai.models.title.discovered", accessMode = Schema.AccessMode.READ_ONLY)
  private Boolean discovered;

  @JsonIgnore
  @Schema(hidden = true)
  private Boolean typeAutoDetected;

  @Schema(title = "i18n:ai.models.title.ownedBy", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 256)
  @Column(length = 256)
  private String ownedBy;

  @Schema(title = "i18n:ai.models.title.releasedAt", accessMode = Schema.AccessMode.READ_ONLY)
  private Instant releasedAt;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(length = 16000)
  private String metadataJson;

  @Order(7)
  @Schema(title = "i18n:ai.models.title.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
