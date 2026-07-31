package org.simplepoint.plugin.ai.workflow.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Persistence contract for durable Workflow executions.
 */
public interface AiWorkflowExecutionRepository
    extends BaseRepository<AiWorkflowExecution, String> {

  /**
   * Finds one non-deleted execution.
   */
  Optional<AiWorkflowExecution> findActiveById(String id);

  /**
   * Locks and finds one non-deleted execution.
   */
  Optional<AiWorkflowExecution> findActiveByIdForUpdate(String id);

  /**
   * Finds a scope-local idempotent execution.
   */
  Optional<AiWorkflowExecution> findActiveByIdempotency(
      String workflowId,
      AiResourceScope scopeType,
      String tenantId,
      String idempotencyKeyHash
  );

  /**
   * Pages executions of one Workflow and exact scope.
   */
  Page<AiWorkflowExecution> findAllActiveByWorkflowAndScope(
      String workflowId,
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );

  /**
   * Claims due or abandoned executions without blocking other workers.
   */
  List<AiWorkflowExecution> findClaimableForUpdate(
      Instant now,
      Pageable pageable
  );
}
