package org.simplepoint.plugin.ai.workflow.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventFeed;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionPauseRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStartRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskResponseRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Scope-aware durable Agent Workflow execution control plane.
 */
public interface AiWorkflowExecutionService {

  /**
   * Starts an idempotent execution of the active published version.
   */
  AiWorkflowExecution start(
      String workflowId,
      WorkflowExecutionStartRequest request
  );

  /**
   * Pages durable executions of one visible Workflow.
   */
  Page<AiWorkflowExecution> findAll(
      String workflowId,
      Pageable pageable
  );

  /**
   * Finds one execution with node and human task checkpoints.
   */
  Optional<AiWorkflowExecution> find(
      String workflowId,
      String executionId
  );

  /**
   * Reads a bounded append-only event page.
   */
  WorkflowExecutionEventFeed findEvents(
      String workflowId,
      String executionId,
      long afterSequence,
      int limit
  );

  /**
   * Requests a cooperative pause at the next safe checkpoint.
   */
  AiWorkflowExecution pause(
      String workflowId,
      String executionId,
      WorkflowExecutionPauseRequest request
  );

  /**
   * Resumes a cooperatively paused execution.
   */
  AiWorkflowExecution resume(
      String workflowId,
      String executionId
  );

  /**
   * Completes one open structured human task.
   */
  AiWorkflowExecution respondHumanTask(
      String workflowId,
      String executionId,
      String taskId,
      WorkflowHumanTaskResponseRequest request
  );

  /**
   * Cancels a non-terminal execution and all open human tasks.
   */
  AiWorkflowExecution cancel(
      String workflowId,
      String executionId
  );
}
