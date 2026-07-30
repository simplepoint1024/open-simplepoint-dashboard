package org.simplepoint.plugin.ai.agent.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatusMetric;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for durable Agent executions.
 */
public interface AiAgentExecutionRepository
    extends BaseRepository<AiAgentExecution, String> {

  /**
   * Finds one non-deleted execution.
   */
  Optional<AiAgentExecution> findActiveById(String id);

  /**
   * Finds and locks one non-deleted execution.
   */
  Optional<AiAgentExecution> findActiveByIdForUpdate(String id);

  /**
   * Finds a prior execution by its scope-local idempotency hash.
   */
  Optional<AiAgentExecution> findActiveByIdempotency(
      String agentId,
      AiResourceScope scopeType,
      String tenantId,
      String idempotencyKeyHash
  );

  /**
   * Pages executions owned by one Agent and scope.
   */
  Page<AiAgentExecution> findAllActiveByAgentAndScope(
      String agentId,
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );

  /**
   * Claims runnable or expired executions without blocking another worker.
   */
  List<AiAgentExecution> findClaimableForUpdate(
      Instant now,
      Pageable pageable
  );

  /**
   * Aggregates executions for one Agent and bounded time window.
   */
  List<AgentExecutionStatusMetric> aggregateByAgentAndWindow(
      String agentId,
      AiResourceScope scopeType,
      String tenantId,
      Instant from,
      Instant to
  );
}
