package org.simplepoint.plugin.ai.workflow.api.model;

/**
 * Durable Agent Workflow execution lifecycle.
 */
public enum WorkflowExecutionStatus {
  PENDING,
  RUNNING,
  WAITING_CHILD,
  WAITING_HUMAN,
  WAITING_TIMER,
  PAUSED,
  COMPENSATING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
