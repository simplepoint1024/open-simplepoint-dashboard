package org.simplepoint.plugin.ai.skill.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
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

  /** Finds a prior idempotent execution for one immutable Draft Revision. */
  Optional<AiSkillExecution> findActiveDraftByIdempotency(
      String skillId,
      String draftId,
      long draftRevision,
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

  /** Pages one Skill's executions for an exact source. */
  Page<AiSkillExecution> findAllActiveBySkillScopeAndSource(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      SkillExecutionSource sourceType,
      Pageable pageable
  );

  /** Finds ordered executions belonging to one durable Mock test run. */
  List<AiSkillExecution> findAllActiveByTestRun(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      String testRunId
  );

  /** Pages distinct durable Mock test Run IDs newest first. */
  Page<String> findAllActiveTestRunIdsBySkillAndScope(
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
