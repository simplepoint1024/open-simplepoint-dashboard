package org.simplepoint.plugin.ai.runtime.api.entity;

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
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Encrypted platform or tenant secret available to OCI workloads by reference.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_secrets",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_secret_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_secret_enabled",
            columnList = "enabled"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Runtime Secret")
public class AiRuntimeSecret extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String tenantId;

  @Column(length = 64, nullable = false)
  private String code;

  @Column(length = 128, nullable = false)
  private String name;

  @Column(length = 512)
  private String description;

  @JsonIgnore
  @Schema(hidden = true)
  @Column(name = "value_ciphertext", length = 131072, nullable = false)
  private String valueCiphertext;

  @Transient
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
  private String value;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean hasValue;

  @Column(nullable = false)
  private Boolean enabled;

  @Column(name = "rotated_at", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Instant rotatedAt;
}
