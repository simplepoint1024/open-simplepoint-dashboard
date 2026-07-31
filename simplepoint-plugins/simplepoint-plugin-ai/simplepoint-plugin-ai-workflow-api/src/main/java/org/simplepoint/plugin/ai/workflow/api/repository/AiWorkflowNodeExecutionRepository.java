package org.simplepoint.plugin.ai.workflow.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;

/**
 * Persistence contract for durable Workflow node checkpoints.
 */
public interface AiWorkflowNodeExecutionRepository
    extends BaseRepository<AiWorkflowNodeExecution, String> {

  /**
   * Lists node checkpoints in deterministic topological order.
   */
  List<AiWorkflowNodeExecution> findAllActiveByExecutionId(
      String executionId
  );

  /**
   * Finds one node checkpoint.
   */
  Optional<AiWorkflowNodeExecution> findActiveByExecutionIdAndNodeId(
      String executionId,
      String nodeId
  );

  /**
   * Locks and finds one node checkpoint.
   */
  Optional<AiWorkflowNodeExecution> findActiveByExecutionIdAndNodeIdForUpdate(
      String executionId,
      String nodeId
  );
}
