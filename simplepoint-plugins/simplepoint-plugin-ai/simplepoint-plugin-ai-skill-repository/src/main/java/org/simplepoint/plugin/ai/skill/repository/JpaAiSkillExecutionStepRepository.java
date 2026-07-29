package org.simplepoint.plugin.ai.skill.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for Skill workflow step checkpoints.
 */
@Repository
public interface JpaAiSkillExecutionStepRepository
    extends BaseRepository<AiSkillExecutionStep, String>,
    AiSkillExecutionStepRepository {

  @Override
  @Query("""
      select step from AiSkillExecutionStep step
      where step.executionId = :executionId
        and step.deletedAt is null
      order by step.stepOrder asc
      """)
  List<AiSkillExecutionStep> findAllActiveByExecutionId(
      @Param("executionId") String executionId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select step from AiSkillExecutionStep step
      where step.executionId = :executionId
        and step.stepId = :stepId
        and step.deletedAt is null
      """)
  Optional<AiSkillExecutionStep> findActiveByExecutionIdAndStepIdForUpdate(
      @Param("executionId") String executionId,
      @Param("stepId") String stepId
  );
}
