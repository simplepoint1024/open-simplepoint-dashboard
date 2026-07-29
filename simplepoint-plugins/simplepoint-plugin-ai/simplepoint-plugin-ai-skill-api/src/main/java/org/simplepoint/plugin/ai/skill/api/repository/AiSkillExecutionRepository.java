package org.simplepoint.plugin.ai.skill.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for durable Skill workflow executions.
 */
public interface AiSkillExecutionRepository
    extends BaseRepository<AiSkillExecution, String> {

  /**
   * Finds one non-deleted execution.
   */
  Optional<AiSkillExecution> findActiveById(String id);

  /**
   * Finds and locks one non-deleted execution.
   */
  Optional<AiSkillExecution> findActiveByIdForUpdate(String id);

  /**
   * Finds a prior idempotent execution in one ownership scope.
   */
  Optional<AiSkillExecution> findActiveByIdempotency(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      String idempotencyKeyHash
  );

  /**
   * Pages one Skill's executions in the selected ownership scope.
   */
  Page<AiSkillExecution> findAllActiveBySkillAndScope(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );

  /**
   * Claims pending or expired executions without blocking another worker.
   */
  List<AiSkillExecution> findClaimableForUpdate(
      Instant now,
      Pageable pageable
  );
}
