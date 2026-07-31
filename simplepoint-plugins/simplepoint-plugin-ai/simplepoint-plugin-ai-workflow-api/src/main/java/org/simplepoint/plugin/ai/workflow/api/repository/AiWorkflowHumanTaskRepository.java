package org.simplepoint.plugin.ai.workflow.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowHumanTask;

/**
 * Persistence contract for durable Workflow human tasks.
 */
public interface AiWorkflowHumanTaskRepository
    extends BaseRepository<AiWorkflowHumanTask, String> {

  /**
   * Lists all tasks of one execution.
   */
  List<AiWorkflowHumanTask> findAllActiveByExecutionId(String executionId);

  /**
   * Finds one task belonging to an execution.
   */
  Optional<AiWorkflowHumanTask> findActiveByIdAndExecutionId(
      String id,
      String executionId
  );

  /**
   * Locks and finds one task belonging to an execution.
   */
  Optional<AiWorkflowHumanTask> findActiveByIdAndExecutionIdForUpdate(
      String id,
      String executionId
  );

  /**
   * Finds a task created for one node checkpoint.
   */
  Optional<AiWorkflowHumanTask> findActiveByNodeExecutionId(
      String nodeExecutionId
  );
}
