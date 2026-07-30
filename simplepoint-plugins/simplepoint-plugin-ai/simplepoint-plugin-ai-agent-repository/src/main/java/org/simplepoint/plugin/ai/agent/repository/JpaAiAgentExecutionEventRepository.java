package org.simplepoint.plugin.ai.agent.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionEventRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable Agent execution events.
 */
@Repository
public interface JpaAiAgentExecutionEventRepository
    extends BaseRepository<AiAgentExecutionEvent, String>,
    AiAgentExecutionEventRepository {

  @Override
  @Query("""
      select count(event) from AiAgentExecutionEvent event
      where event.executionId = :executionId and event.deletedAt is null
      """)
  long countActiveByExecutionId(
      @Param("executionId") String executionId
  );

  @Override
  @Query("""
      select event from AiAgentExecutionEvent event
      where event.executionId = :executionId
        and event.sequence > :afterSequence
        and event.deletedAt is null
      order by event.sequence asc
      """)
  List<AiAgentExecutionEvent> findActiveAfterSequence(
      @Param("executionId") String executionId,
      @Param("afterSequence") long afterSequence,
      Pageable pageable
  );
}
