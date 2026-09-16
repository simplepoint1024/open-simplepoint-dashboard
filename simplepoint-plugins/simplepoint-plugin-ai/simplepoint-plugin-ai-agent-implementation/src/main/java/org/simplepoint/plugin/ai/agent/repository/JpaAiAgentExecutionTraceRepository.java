package org.simplepoint.plugin.ai.agent.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for Agent execution traces.
 */
@Repository
public interface JpaAiAgentExecutionTraceRepository
    extends BaseRepository<AiAgentExecutionTrace, String>,
    AiAgentExecutionTraceRepository {

  @Override
  @Query("""
      select trace from AiAgentExecutionTrace trace
      where trace.id = :id and trace.deletedAt is null
      """)
  Optional<AiAgentExecutionTrace> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select trace from AiAgentExecutionTrace trace
      where trace.executionId = :executionId and trace.deletedAt is null
      order by trace.sequence asc
      """)
  List<AiAgentExecutionTrace> findAllActiveByExecutionId(
      @Param("executionId") String executionId
  );

  @Override
  @Query("""
      select trace from AiAgentExecutionTrace trace
      where trace.executionId = :executionId
        and (:type is null or trace.type = :type)
        and (:status is null or trace.status = :status)
        and trace.deletedAt is null
      order by trace.sequence asc
      """)
  Page<AiAgentExecutionTrace> findAllActiveByExecutionId(
      @Param("executionId") String executionId,
      @Param("type") AgentTraceType type,
      @Param("status") AgentTraceStatus status,
      Pageable pageable
  );

  @Override
  @Query("""
      select count(trace) from AiAgentExecutionTrace trace
      where trace.executionId = :executionId and trace.deletedAt is null
      """)
  long countActiveByExecutionId(@Param("executionId") String executionId);

  @Override
  @Query("""
      select new org.simplepoint.plugin.ai.agent.api.model.AgentTraceMetric(
        trace.type,
        trace.status,
        count(trace),
        sum(trace.inputTokens),
        sum(trace.outputTokens),
        sum(trace.cost)
      )
      from AiAgentExecutionTrace trace, AiAgentExecution execution
      where trace.executionId = execution.id
        and execution.agentId = :agentId
        and execution.createdAt >= :from
        and execution.createdAt < :to
        and execution.deletedAt is null
        and trace.deletedAt is null
      group by trace.type, trace.status
      order by trace.type, trace.status
      """)
  List<AgentTraceMetric> aggregateByAgentAndWindow(
      @Param("agentId") String agentId,
      @Param("from") java.time.Instant from,
      @Param("to") java.time.Instant to
  );
}
