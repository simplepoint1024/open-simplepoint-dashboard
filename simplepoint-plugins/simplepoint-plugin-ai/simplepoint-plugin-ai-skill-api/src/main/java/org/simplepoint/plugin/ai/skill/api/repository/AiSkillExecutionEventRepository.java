package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.springframework.data.domain.Pageable;

/** Persistence contract for append-only Skill execution events. */
public interface AiSkillExecutionEventRepository
    extends BaseRepository<AiSkillExecutionEvent, String> {

  /** Returns the greatest allocated execution-local sequence, or zero. */
  long findMaximumSequence(String executionId);

  /** Reads a stable event page after an exclusive cursor. */
  List<AiSkillExecutionEvent> findActiveAfterSequence(
      String executionId,
      long afterSequence,
      Pageable pageable
  );
}
