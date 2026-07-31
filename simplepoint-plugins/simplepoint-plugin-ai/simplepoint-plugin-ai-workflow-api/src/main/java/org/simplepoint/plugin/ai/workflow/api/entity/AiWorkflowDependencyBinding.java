package org.simplepoint.plugin.ai.workflow.api.entity;

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
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType;

/**
 * Exact immutable Agent or Skill dependency pinned by a Workflow node.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_workflow_dependencies",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_workflow_dependency_version",
            columnList = "workflow_version_id, binding_order"
        ),
        @Index(
            name = "idx_simpoint_ai_workflow_dependency_resource",
            columnList = "resource_version_id"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI Workflow Dependency")
public class AiWorkflowDependencyBinding extends BaseEntityImpl<String> {

  @Column(name = "workflow_version_id", length = 64, nullable = false)
  private String workflowVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "node_id", length = 64, nullable = false)
  private String nodeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "dependency_type", length = 32, nullable = false)
  private WorkflowDependencyType dependencyType;

  @Column(name = "resource_id", length = 64, nullable = false)
  private String resourceId;

  @Column(name = "resource_version_id", length = 64, nullable = false)
  private String resourceVersionId;

  @Column(name = "resource_code", length = 64, nullable = false)
  private String resourceCode;

  @Column(name = "resource_version_name", length = 64, nullable = false)
  private String resourceVersionName;

  @Column(name = "resource_content_hash", length = 64, nullable = false)
  private String resourceContentHash;

  @Column(name = "binding_order", nullable = false)
  private Integer bindingOrder;
}
