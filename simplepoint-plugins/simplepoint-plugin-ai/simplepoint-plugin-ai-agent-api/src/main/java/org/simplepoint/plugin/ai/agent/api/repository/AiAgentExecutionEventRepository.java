package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for append-only Agent execution events.
 */
public interface AiAgentExecutionEventRepository
    extends BaseRepository<AiAgentExecutionEvent, String> {

  /**
   * Counts events for execution-local sequence allocation.
   */
  long countActiveByExecutionId(String executionId);

  /**
   * Reads events after an exclusive cursor in stable order.
   */
  List<AiAgentExecutionEvent> findActiveAfterSequence(
      String executionId,
      long afterSequence,
      Pageable pageable
  );
}
