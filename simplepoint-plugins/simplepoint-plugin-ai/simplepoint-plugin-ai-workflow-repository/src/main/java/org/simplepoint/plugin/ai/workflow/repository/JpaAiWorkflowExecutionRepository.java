package org.simplepoint.plugin.ai.workflow.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable Workflow executions.
 */
@Repository
public interface JpaAiWorkflowExecutionRepository
    extends BaseRepository<AiWorkflowExecution, String>,
    AiWorkflowExecutionRepository {

  @Override
  @Query("""
      select execution from AiWorkflowExecution execution
      where execution.id = :id and execution.deletedAt is null
      """)
  Optional<AiWorkflowExecution> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select execution from AiWorkflowExecution execution
      where execution.id = :id and execution.deletedAt is null
      """)
  Optional<AiWorkflowExecution> findActiveByIdForUpdate(
      @Param("id") String id
  );

  @Override
  @Query("""
      select execution from AiWorkflowExecution execution
      where execution.workflowId = :workflowId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.idempotencyKeyHash = :idempotencyKeyHash
        and execution.deletedAt is null
      """)
  Optional<AiWorkflowExecution> findActiveByIdempotency(
      @Param("workflowId") String workflowId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("idempotencyKeyHash") String idempotencyKeyHash
  );

  @Override
  @Query("""
      select execution from AiWorkflowExecution execution
      where execution.workflowId = :workflowId
        and execution.scopeType = :scopeType
        and ((:tenantId is null and execution.tenantId is null)
          or execution.tenantId = :tenantId)
        and execution.deletedAt is null
      order by execution.createdAt desc
      """)
  Page<AiWorkflowExecution> findAllActiveByWorkflowAndScope(
      @Param("workflowId") String workflowId,
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
      select execution from AiWorkflowExecution execution
      where (
          execution.status =
            org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus.PENDING
          or (
            execution.status =
              org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus.COMPENSATING
            and (
              execution.nextPollAt is null
              or execution.nextPollAt <= :now
            )
          )
          or (
            execution.status =
              org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus.RUNNING
            and execution.leaseExpiresAt <= :now
          )
          or (
            execution.status in (
              org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus.WAITING_CHILD,
              org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus.WAITING_HUMAN,
              org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus.WAITING_TIMER
            )
            and execution.nextPollAt <= :now
          )
        )
        and execution.deletedAt is null
      order by execution.createdAt asc
      """)
  List<AiWorkflowExecution> findClaimableForUpdate(
      @Param("now") Instant now,
      Pageable pageable
  );
}
