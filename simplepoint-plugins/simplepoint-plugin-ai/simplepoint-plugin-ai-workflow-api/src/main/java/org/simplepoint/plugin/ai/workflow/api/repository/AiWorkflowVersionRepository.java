package org.simplepoint.plugin.ai.workflow.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Persistence contract for immutable Workflow versions.
 */
public interface AiWorkflowVersionRepository
    extends BaseRepository<AiWorkflowVersion, String> {

  /**
   * Finds a non-deleted immutable version by ID.
   */
  Optional<AiWorkflowVersion> findActiveById(String id);

  /**
   * Finds a non-deleted immutable version belonging to a Workflow.
   */
  Optional<AiWorkflowVersion> findActiveByIdAndWorkflowId(
      String id,
      String workflowId
  );

  /**
   * Locks and finds an immutable version belonging to a Workflow.
   */
  Optional<AiWorkflowVersion> findActiveByIdAndWorkflowIdForUpdate(
      String id,
      String workflowId
  );

  /**
   * Finds one semantic version belonging to a Workflow.
   */
  Optional<AiWorkflowVersion> findActiveByVersionAndWorkflowId(
      String version,
      String workflowId
  );

  /**
   * Pages immutable versions belonging to a Workflow.
   */
  Page<AiWorkflowVersion> findAllActiveByWorkflowId(
      String workflowId,
      Pageable pageable
  );

  /**
   * Counts immutable versions belonging to a Workflow.
   */
  long countActiveByWorkflowId(String workflowId);
}
