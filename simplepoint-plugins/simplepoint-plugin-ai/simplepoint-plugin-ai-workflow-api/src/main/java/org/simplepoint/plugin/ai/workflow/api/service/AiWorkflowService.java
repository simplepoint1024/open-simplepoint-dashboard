package org.simplepoint.plugin.ai.workflow.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyOption;
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
   * Searches selectable Agent or Skill dependencies for one Workflow.
   */
  Page<AiDependencyOption> findDependencyOptions(
      String workflowId,
      AiDependencyKind kind,
      String query,
      int page,
      int size
  );

  /**
   * Resolves current and historical Agent or Skill dependency labels.
   */
  List<AiDependencyOption> resolveDependencyOptions(
      String workflowId,
      AiDependencyKind kind,
      Collection<String> optionIds
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
