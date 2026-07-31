package org.simplepoint.plugin.ai.workflow.api.entity;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionStatus;

/**
 * Immutable Workflow manifest and mutable publication state.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_workflow_versions",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_workflow_version_workflow",
            columnList = "workflow_id"
        ),
        @Index(
            name = "idx_simpoint_ai_workflow_version_scope",
            columnList = "scope_type, tenant_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Workflow Version")
public class AiWorkflowVersion extends BaseEntityImpl<String> {

  @Column(name = "workflow_id", length = 64, nullable = false)
  private String workflowId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "version_name", length = 64, nullable = false)
  private String version;

  @Column(name = "manifest_schema_version", length = 32, nullable = false)
  private String manifestSchemaVersion;

  @Column(name = "content_hash", length = 64, nullable = false)
  private String contentHash;

  @JsonIgnore
  @Column(name = "manifest_json", columnDefinition = "TEXT", nullable = false)
  private String manifestJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private WorkflowVersionStatus status;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "deprecated_at")
  private Instant deprecatedAt;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, Object> manifest;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<AiWorkflowDependencyBinding> dependencies;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
