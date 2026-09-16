package org.simplepoint.plugin.ai.skill.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPublishTaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for durable Skill publication tasks. */
@Repository
public interface JpaAiSkillPublishTaskRepository
    extends BaseRepository<AiSkillPublishTask, String>,
    AiSkillPublishTaskRepository {

  @Override
  @Query("""
      select task from AiSkillPublishTask task
      where task.id = :id and task.deletedAt is null
      """)
  Optional<AiSkillPublishTask> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select task from AiSkillPublishTask task
      where task.id = :id and task.deletedAt is null
      """)
  Optional<AiSkillPublishTask> findActiveByIdForUpdate(@Param("id") String id);

  @Override
  @Query("""
      select task from AiSkillPublishTask task
      where task.skillId = :skillId
        and task.scopeType = :scopeType
        and ((:tenantId is null and task.tenantId is null)
          or task.tenantId = :tenantId)
        and task.idempotencyKeyHash = :idempotencyKeyHash
        and task.deletedAt is null
      """)
  Optional<AiSkillPublishTask> findActiveByIdempotency(
      @Param("skillId") String skillId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("idempotencyKeyHash") String idempotencyKeyHash
  );

  @Override
  @Query("""
      select task from AiSkillPublishTask task
      where task.skillId = :skillId
        and task.scopeType = :scopeType
        and ((:tenantId is null and task.tenantId is null)
          or task.tenantId = :tenantId)
        and task.deletedAt is null
      order by task.createdAt desc
      """)
  Page<AiSkillPublishTask> findAllActiveBySkillAndScope(
      @Param("skillId") String skillId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select task from AiSkillPublishTask task
      where (
          (
            task.status =
              org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStatus.PENDING
            and task.nextAttemptAt <= :now
          )
          or (
            task.status =
              org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStatus.RUNNING
            and task.leaseExpiresAt <= :now
          )
        )
        and task.deletedAt is null
      order by task.createdAt asc
      """)
  List<AiSkillPublishTask> findClaimableForUpdate(
      @Param("now") Instant now,
      Pageable pageable
  );
}
