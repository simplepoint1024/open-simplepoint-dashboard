package org.simplepoint.plugin.ai.agent.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatusMetric;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable Agent executions.
 */
@Repository
public interface JpaAiAgentExecutionRepository
    extends BaseRepository<AiAgentExecution, String>,
    AiAgentExecutionRepository {

  @Override
  @Query("""
      select execution from AiAgentExecution execution
      where execution.id = :id and execution.deletedAt is null
      """)
  Optional<AiAgentExecution> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select execution from AiAgentExecution execution
      where execution.id = :id and execution.deletedAt is null
      """)
  Optional<AiAgentExecution> findActiveByIdForUpdate(@Param("id") String id);

  @Override
  @Query("""
      select execution from AiAgentExecution execution
      where execution.agentId = :agentId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.idempotencyKeyHash = :idempotencyKeyHash
        and execution.deletedAt is null
      """)
  Optional<AiAgentExecution> findActiveByIdempotency(
      @Param("agentId") String agentId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("idempotencyKeyHash") String idempotencyKeyHash
  );

  @Override
  @Query("""
      select execution from AiAgentExecution execution
      where execution.agentId = :agentId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.deletedAt is null
      order by execution.createdAt desc
      """)
  Page<AiAgentExecution> findAllActiveByAgentAndScope(
      @Param("agentId") String agentId,
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
      select execution from AiAgentExecution execution
      where (
          execution.status =
            org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus.PENDING
          or (
            execution.status =
              org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus.WAITING_SKILL
            and execution.nextPollAt <= :now
          )
          or (
            execution.status =
              org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus.WAITING_HUMAN
            and execution.nextPollAt <= :now
          )
          or (
            execution.status =
              org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus.RUNNING
            and execution.leaseExpiresAt <= :now
          )
        )
        and execution.deletedAt is null
      order by execution.createdAt asc
      """)
  List<AiAgentExecution> findClaimableForUpdate(
      @Param("now") Instant now,
      Pageable pageable
  );

  @Override
  @Query("""
      select new org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatusMetric(
        execution.status,
        count(execution),
        sum(execution.consumedInputTokens),
        sum(execution.consumedOutputTokens),
        sum(execution.consumedCost),
        sum(execution.stepCount),
        sum(execution.humanInterventionCount)
      )
      from AiAgentExecution execution
      where execution.agentId = :agentId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.createdAt >= :from
        and execution.createdAt < :to
        and execution.deletedAt is null
      group by execution.status
      order by execution.status
      """)
  List<AgentExecutionStatusMetric> aggregateByAgentAndWindow(
      @Param("agentId") String agentId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to
  );
}
