package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;

/**
 * Repository contract for durable Skill workflow step checkpoints.
 */
public interface AiSkillExecutionStepRepository
    extends BaseRepository<AiSkillExecutionStep, String> {

  /**
   * Lists ordered, non-deleted checkpoints for one execution.
   */
  List<AiSkillExecutionStep> findAllActiveByExecutionId(String executionId);

  /**
   * Finds and locks one execution step checkpoint.
   */
  Optional<AiSkillExecutionStep> findActiveByExecutionIdAndStepIdForUpdate(
      String executionId,
      String stepId
  );
}
