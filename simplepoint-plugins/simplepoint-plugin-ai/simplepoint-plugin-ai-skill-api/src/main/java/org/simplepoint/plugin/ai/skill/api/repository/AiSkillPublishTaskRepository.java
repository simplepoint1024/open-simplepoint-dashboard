package org.simplepoint.plugin.ai.skill.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence contract for durable Skill publication tasks. */
public interface AiSkillPublishTaskRepository
    extends BaseRepository<AiSkillPublishTask, String> {

  /** Finds one active task without a row lock. */
  Optional<AiSkillPublishTask> findActiveById(String id);

  /** Finds one active task with a write lock. */
  Optional<AiSkillPublishTask> findActiveByIdForUpdate(String id);

  /** Finds one task by its scope-aware idempotency boundary. */
  Optional<AiSkillPublishTask> findActiveByIdempotency(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      String idempotencyKeyHash
  );

  /** Pages active tasks owned by one Skill and management scope. */
  Page<AiSkillPublishTask> findAllActiveBySkillAndScope(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );

  /** Locks due pending or expired-running tasks for worker claims. */
  List<AiSkillPublishTask> findClaimableForUpdate(
      Instant now,
      Pageable pageable
  );
}
