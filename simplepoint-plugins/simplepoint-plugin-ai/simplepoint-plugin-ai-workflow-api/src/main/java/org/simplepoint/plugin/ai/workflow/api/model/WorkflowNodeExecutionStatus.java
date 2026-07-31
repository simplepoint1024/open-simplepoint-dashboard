package org.simplepoint.plugin.ai.workflow.api.model;

/**
 * Durable state of one declarative Workflow node.
 */
public enum WorkflowNodeExecutionStatus {
  PENDING,
  RUNNING,
  WAITING,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  COMPENSATING,
  COMPENSATED,
  COMPENSATION_FAILED
}
