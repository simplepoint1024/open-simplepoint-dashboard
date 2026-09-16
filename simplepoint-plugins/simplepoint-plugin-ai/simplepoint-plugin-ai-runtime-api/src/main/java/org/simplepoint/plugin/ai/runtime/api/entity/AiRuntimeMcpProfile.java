package org.simplepoint.plugin.ai.runtime.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileStatus;

/** Mutable draft identity whose published deployments are immutable revisions. */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_mcp_profiles",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_profile_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_profile_descriptor",
            columnList = "descriptor_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI MCP Runtime Profile")
public class AiRuntimeMcpProfile extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "descriptor_id", length = 64, nullable = false)
  private String descriptorId;

  @Column(length = 64, nullable = false)
  private String code;

  @Column(length = 128, nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private RuntimeMcpProfileStatus status;

  @JsonIgnore
  @Column(name = "spec_json", columnDefinition = "TEXT", nullable = false)
  private String specJson;

  @Column(name = "spec_hash", length = 64, nullable = false)
  private String specHash;

  @Column(name = "active_revision_id", length = 64)
  private String activeRevisionId;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private RuntimeMcpProfileSpec spec;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
