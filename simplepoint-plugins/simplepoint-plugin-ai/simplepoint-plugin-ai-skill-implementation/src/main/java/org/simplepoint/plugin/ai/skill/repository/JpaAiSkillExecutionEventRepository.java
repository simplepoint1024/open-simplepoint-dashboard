package org.simplepoint.plugin.ai.skill.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionEventRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for durable Skill execution events. */
@Repository
public interface JpaAiSkillExecutionEventRepository
    extends BaseRepository<AiSkillExecutionEvent, String>,
    AiSkillExecutionEventRepository {

  @Override
  @Query("""
      select coalesce(max(event.sequence), 0)
      from AiSkillExecutionEvent event
      where event.executionId = :executionId and event.deletedAt is null
      """)
  long findMaximumSequence(@Param("executionId") String executionId);

  @Override
  @Query("""
      select event from AiSkillExecutionEvent event
      where event.executionId = :executionId
        and event.sequence > :afterSequence
        and event.deletedAt is null
      order by event.sequence asc
      """)
  List<AiSkillExecutionEvent> findActiveAfterSequence(
      @Param("executionId") String executionId,
      @Param("afterSequence") long afterSequence,
      Pageable pageable
  );
}
