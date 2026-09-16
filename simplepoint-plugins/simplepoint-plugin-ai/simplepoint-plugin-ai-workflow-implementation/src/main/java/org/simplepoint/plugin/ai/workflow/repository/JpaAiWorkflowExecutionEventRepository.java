package org.simplepoint.plugin.ai.workflow.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecutionEvent;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionEventRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for append-only Workflow events.
 */
@Repository
public interface JpaAiWorkflowExecutionEventRepository
    extends BaseRepository<AiWorkflowExecutionEvent, String>,
    AiWorkflowExecutionEventRepository {

  @Override
  @Query("""
      select event from AiWorkflowExecutionEvent event
      where event.executionId = :executionId
        and event.sequence > :afterSequence
        and event.deletedAt is null
      order by event.sequence asc
      """)
  List<AiWorkflowExecutionEvent> findAfterSequence(
      @Param("executionId") String executionId,
      @Param("afterSequence") long afterSequence,
      Pageable pageable
  );

  @Override
  @Query("""
      select coalesce(max(event.sequence), 0)
      from AiWorkflowExecutionEvent event
      where event.executionId = :executionId
        and event.deletedAt is null
      """)
  long findMaximumSequence(@Param("executionId") String executionId);
}
