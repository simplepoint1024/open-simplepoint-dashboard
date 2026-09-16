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
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;

/** Immutable published snapshot of one descriptor and runtime profile. */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_mcp_revisions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_revision_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_revision_profile",
            columnList = "profile_id, revision_number"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI MCP Runtime Deployment Revision")
public class AiRuntimeMcpRevision extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "profile_id", length = 64, nullable = false)
  private String profileId;

  @Column(name = "descriptor_id", length = 64, nullable = false)
  private String descriptorId;

  @Column(name = "revision_number", nullable = false)
  private long revisionNumber;

  @Column(name = "descriptor_content_hash", length = 64, nullable = false)
  private String descriptorContentHash;

  @JsonIgnore
  @Column(name = "profile_json", columnDefinition = "TEXT", nullable = false)
  private String profileJson;

  @Column(name = "profile_hash", length = 64, nullable = false)
  private String profileHash;

  @Column(name = "image_reference", length = 512)
  private String imageReference;

  @Column(name = "image_digest", length = 71)
  private String imageDigest;

  @Column(name = "admission_report_hash", length = 64)
  private String admissionReportHash;

  @JsonIgnore
  @Column(name = "admission_report_json", columnDefinition = "TEXT")
  @Schema(hidden = true)
  private String admissionReportJson;

  @Column(name = "published_at", nullable = false)
  private Instant publishedAt;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private RuntimeMcpProfileSpec profileSpec;
}
