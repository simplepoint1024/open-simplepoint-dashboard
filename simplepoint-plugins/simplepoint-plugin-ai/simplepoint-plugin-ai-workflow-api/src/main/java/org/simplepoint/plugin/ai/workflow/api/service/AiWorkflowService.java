package org.simplepoint.plugin.ai.workflow.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowUpsertRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionCreateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Current-scope Workflow registry and immutable version lifecycle.
 */
public interface AiWorkflowService {

  /**
   * Pages Workflows in the current management scope.
   */
  Page<AiWorkflowDefinition> findAll(Pageable pageable);

  /**
   * Finds one visible Workflow.
   */
  Optional<AiWorkflowDefinition> find(String id);

  /**
   * Creates a Workflow in the current management scope.
   */
  AiWorkflowDefinition create(WorkflowUpsertRequest request);

  /**
   * Updates mutable Workflow metadata.
   */
  AiWorkflowDefinition update(
      String id,
      WorkflowUpsertRequest request
  );

  /**
   * Soft deletes a Workflow without immutable versions.
   */
  void remove(String id);

  /**
   * Pages immutable versions of a Workflow.
   */
  Page<AiWorkflowVersion> findVersions(
      String workflowId,
      Pageable pageable
  );

  /**
   * Finds one immutable version of a Workflow.
   */
  Optional<AiWorkflowVersion> findVersion(
      String workflowId,
      String versionId
  );

  /**
   * Creates and dependency-pins an immutable Workflow version.
   */
  AiWorkflowVersion createVersion(
      String workflowId,
      WorkflowVersionCreateRequest request
  );

  /**
   * Publishes and activates an immutable Workflow version.
   */
  AiWorkflowVersion publishVersion(
      String workflowId,
      String versionId
  );

  /**
   * Deprecates an immutable Workflow version.
   */
  AiWorkflowVersion deprecateVersion(
      String workflowId,
      String versionId
  );
}
