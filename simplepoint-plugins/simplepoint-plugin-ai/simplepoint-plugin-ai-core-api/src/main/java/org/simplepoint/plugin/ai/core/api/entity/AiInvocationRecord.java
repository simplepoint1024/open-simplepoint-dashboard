package org.simplepoint.plugin.ai.core.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.simplepoint.plugin.ai.core.api.model.AiInvocationOperation;
import org.simplepoint.plugin.ai.core.api.model.AiInvocationStatus;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.core.annotation.Order;

/**
 * Metadata-only AI usage ledger. Prompts and model output are deliberately not persisted.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_invocations",
    indexes = {
        @Index(name = "idx_simpoint_ai_invocation_scope", columnList = "scope_type, tenant_id"),
        @Index(name = "idx_simpoint_ai_invocation_model", columnList = "model_definition_id"),
        @Index(name = "idx_simpoint_ai_invocation_started", columnList = "started_at")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "i18n:ai.invocations.entity.title")
public class AiInvocationRecord extends BaseEntityImpl<String> {

  @Version
  @Schema(hidden = true)
  private Long version;

  @Order(0)
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.scopeType",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private AiResourceScope scopeType;

  @Order(1)
  @Column(name = "tenant_id", length = 64)
  @Schema(title = "i18n:ai.invocations.title.tenantId")
  private String tenantId;

  @Order(2)
  @Column(name = "user_id", length = 64)
  @Schema(title = "i18n:ai.invocations.title.userId",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String userId;

  @Column(name = "context_id", length = 64)
  @Schema(title = "i18n:ai.invocations.title.contextId")
  private String contextId;

  @Order(3)
  @Column(name = "provider_definition_id", length = 64, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.providerDefinitionId")
  private String providerDefinitionId;

  @Order(4)
  @Column(name = "model_definition_id", length = 64, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.modelDefinitionId")
  private String modelDefinitionId;

  @Order(5)
  @Column(name = "model_id", length = 256, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.modelId",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String modelId;

  @Order(6)
  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", length = 32, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.providerType")
  private AiProviderType providerType;

  @Order(7)
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.operation",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private AiInvocationOperation operation;

  @Schema(title = "i18n:ai.invocations.title.stream")
  private Boolean stream;

  @Order(8)
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(title = "i18n:ai.invocations.title.status",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private AiInvocationStatus status;

  @Column(name = "provider_request_id", length = 256)
  @Schema(title = "i18n:ai.invocations.title.providerRequestId")
  private String providerRequestId;

  @Order(9)
  @Column(name = "started_at", nullable = false)
  @Schema(title = "i18n:ai.invocations.title.startedAt",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Instant startedAt;

  @Column(name = "completed_at")
  @Schema(title = "i18n:ai.invocations.title.completedAt")
  private Instant completedAt;

  @Order(10)
  @Column(name = "duration_millis")
  @Schema(title = "i18n:ai.invocations.title.durationMillis",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Long durationMillis;

  @Order(11)
  @Column(name = "input_tokens")
  @Schema(title = "i18n:ai.invocations.title.inputTokens",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Integer inputTokens;

  @Order(12)
  @Column(name = "output_tokens")
  @Schema(title = "i18n:ai.invocations.title.outputTokens",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Integer outputTokens;

  @Order(13)
  @Column(name = "total_tokens")
  @Schema(title = "i18n:ai.invocations.title.totalTokens",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Integer totalTokens;

  @Column(name = "cached_input_tokens")
  @Schema(title = "i18n:ai.invocations.title.cachedInputTokens")
  private Integer cachedInputTokens;

  @Column(name = "error_code", length = 128)
  @Schema(title = "i18n:ai.invocations.title.errorCode")
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  @Schema(title = "i18n:ai.invocations.title.errorMessage")
  private String errorMessage;
}
