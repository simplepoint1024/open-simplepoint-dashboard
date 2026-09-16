package org.simplepoint.plugin.ai.runtime.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** Immutable, scope-owned copy of an upstream MCP server descriptor. */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_mcp_descriptors",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_descriptor_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_descriptor_name",
            columnList = "registry_name, server_version"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI MCP Runtime Descriptor")
public class AiRuntimeMcpDescriptor extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "source_catalog_entry_id", length = 64)
  private String sourceCatalogEntryId;

  @Column(name = "registry_name", length = 512, nullable = false)
  private String registryName;

  @Column(name = "server_version", length = 128, nullable = false)
  private String serverVersion;

  @Column(length = 256)
  private String title;

  @Column(name = "repository_url", length = 2048)
  private String repositoryUrl;

  @Column(name = "content_hash", length = 64, nullable = false)
  private String contentHash;

  @JsonIgnore
  @Column(name = "descriptor_json", columnDefinition = "TEXT", nullable = false)
  private String descriptorJson;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
