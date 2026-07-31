package org.simplepoint.plugin.ai.workflow.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecutionEvent;
import org.springframework.data.domain.Pageable;

/**
 * Persistence contract for append-only Workflow events.
 */
public interface AiWorkflowExecutionEventRepository
    extends BaseRepository<AiWorkflowExecutionEvent, String> {

  /**
   * Finds a bounded event page after an exclusive sequence cursor.
   */
  List<AiWorkflowExecutionEvent> findAfterSequence(
      String executionId,
      long afterSequence,
      Pageable pageable
  );

  /**
   * Returns the last allocated event sequence.
   */
  long findMaximumSequence(String executionId);
}
