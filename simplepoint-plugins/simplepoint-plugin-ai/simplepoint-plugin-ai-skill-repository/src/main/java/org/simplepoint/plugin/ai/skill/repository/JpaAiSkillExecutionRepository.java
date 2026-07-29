package org.simplepoint.plugin.ai.skill.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable Skill workflow executions.
 */
@Repository
public interface JpaAiSkillExecutionRepository
    extends BaseRepository<AiSkillExecution, String>,
    AiSkillExecutionRepository {

  @Override
  @Query("""
      select execution from AiSkillExecution execution
      where execution.id = :id and execution.deletedAt is null
      """)
  Optional<AiSkillExecution> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select execution from AiSkillExecution execution
      where execution.id = :id and execution.deletedAt is null
      """)
  Optional<AiSkillExecution> findActiveByIdForUpdate(@Param("id") String id);

  @Override
  @Query("""
      select execution from AiSkillExecution execution
      where execution.skillId = :skillId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.idempotencyKeyHash = :idempotencyKeyHash
        and execution.deletedAt is null
      """)
  Optional<AiSkillExecution> findActiveByIdempotency(
      @Param("skillId") String skillId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("idempotencyKeyHash") String idempotencyKeyHash
  );

  @Override
  @Query("""
      select execution from AiSkillExecution execution
      where execution.skillId = :skillId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.deletedAt is null
      order by execution.createdAt desc
      """)
  Page<AiSkillExecution> findAllActiveBySkillAndScope(
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
      select execution from AiSkillExecution execution
      where (
          execution.status =
            org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus.PENDING
          or (
            execution.status =
              org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus.RUNNING
            and execution.leaseExpiresAt <= :now
          )
        )
        and execution.deletedAt is null
      order by execution.createdAt asc
      """)
  List<AiSkillExecution> findClaimableForUpdate(
      @Param("now") Instant now,
      Pageable pageable
  );
}
