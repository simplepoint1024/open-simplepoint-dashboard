package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for Agent execution traces.
 */
public interface AiAgentExecutionTraceRepository
    extends BaseRepository<AiAgentExecutionTrace, String> {

  /**
   * Finds one non-deleted trace.
   */
  Optional<AiAgentExecutionTrace> findActiveById(String id);

  /**
   * Lists traces in stable execution order.
   */
  List<AiAgentExecutionTrace> findAllActiveByExecutionId(String executionId);

  /**
   * Pages execution traces with optional low-cardinality filters.
   */
  Page<AiAgentExecutionTrace> findAllActiveByExecutionId(
      String executionId,
      AgentTraceType type,
      AgentTraceStatus status,
      Pageable pageable
  );

  /**
   * Counts traces for sequence allocation.
   */
  long countActiveByExecutionId(String executionId);

  /**
   * Aggregates traces for one Agent and bounded time window.
   */
  List<AgentTraceMetric> aggregateByAgentAndWindow(
      String agentId,
      java.time.Instant from,
      java.time.Instant to
  );
}
