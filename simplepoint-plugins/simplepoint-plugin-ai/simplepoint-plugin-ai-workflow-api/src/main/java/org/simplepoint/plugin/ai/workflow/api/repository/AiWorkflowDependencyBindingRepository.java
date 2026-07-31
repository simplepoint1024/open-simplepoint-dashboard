package org.simplepoint.plugin.ai.workflow.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDependencyBinding;

/**
 * Persistence contract for pinned Workflow dependencies.
 */
public interface AiWorkflowDependencyBindingRepository
    extends BaseRepository<AiWorkflowDependencyBinding, String> {

  /**
   * Lists pinned dependencies of one immutable Workflow version.
   */
  List<AiWorkflowDependencyBinding> findAllActiveByWorkflowVersionId(
      String workflowVersionId
  );
}
