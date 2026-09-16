package org.simplepoint.plugin.ai.agent.api.service;

import java.time.Instant;
import java.util.Optional;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionDecisionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventFeed;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionMetrics;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionPauseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStartRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionResponseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Scope-aware Agent execution control-plane API.
 */
public interface AiAgentExecutionService {

  /**
   * Starts an idempotent execution of the active Agent version.
   */
  AiAgentExecution start(
      String agentId,
      AgentExecutionStartRequest request
  );

  /**
   * Starts the exact published Agent version pinned by a Workflow.
   *
   * <p>This internal Java contract is not exposed as a public REST endpoint.</p>
   */
  AiAgentExecution startVersionForWorkflow(
      AgentWorkflowExecutionCommand command
  );

  /**
   * Cancels the exact child execution pinned by a parent runtime.
   *
   * <p>This transaction-bound Java contract is not exposed as a public REST
   * endpoint and does not use the current management scope.</p>
   */
  AiAgentExecution cancelPinnedChild(
      AgentPinnedChildCancelCommand command
  );

  /**
   * Pages durable executions for one visible Agent.
   */
  Page<AiAgentExecution> findAll(String agentId, Pageable pageable);

  /**
   * Finds one visible execution with its trace.
   */
  Optional<AiAgentExecution> find(String agentId, String executionId);

  /**
   * Pages traces for one visible execution.
   */
  Page<AiAgentExecutionTrace> findTraces(
      String agentId,
      String executionId,
      AgentTraceType type,
      AgentTraceStatus status,
      Pageable pageable
  );

  /**
   * Reads a bounded event page after an exclusive sequence cursor.
   */
  AgentExecutionEventFeed findEvents(
      String agentId,
      String executionId,
      long afterSequence,
      int limit
  );

  /**
   * Returns persistent execution and trace aggregations.
   */
  AgentExecutionMetrics metrics(
      String agentId,
      Instant from,
      Instant to
  );

  /**
   * Approves an execution waiting on its immutable policy.
   */
  AiAgentExecution approve(
      String agentId,
      String executionId,
      AgentExecutionDecisionRequest request
  );

  /**
   * Rejects an execution waiting on its immutable policy.
   */
  AiAgentExecution reject(
      String agentId,
      String executionId,
      AgentExecutionDecisionRequest request
  );

  /**
   * Requests a cooperative pause at the next safe execution checkpoint.
   */
  AiAgentExecution pause(
      String agentId,
      String executionId,
      AgentExecutionPauseRequest request
  );

  /**
   * Resumes a cooperatively paused execution.
   */
  AiAgentExecution resume(String agentId, String executionId);

  /**
   * Requests human input at the next safe execution checkpoint.
   */
  AiAgentExecution requestHumanIntervention(
      String agentId,
      String executionId,
      AgentHumanInterventionRequest request
  );

  /**
   * Resolves a durable human-intervention wait.
   */
  AiAgentExecution respondHumanIntervention(
      String agentId,
      String executionId,
      String interventionId,
      AgentHumanInterventionResponseRequest request
  );

  /**
   * Cancels a non-terminal execution.
   */
  AiAgentExecution cancel(String agentId, String executionId);
}
