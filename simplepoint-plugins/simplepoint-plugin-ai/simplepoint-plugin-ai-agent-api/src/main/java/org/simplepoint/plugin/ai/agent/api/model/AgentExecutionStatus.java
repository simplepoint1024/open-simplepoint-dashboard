package org.simplepoint.plugin.ai.agent.api.model;

/**
 * Durable lifecycle of one immutable Agent version execution.
 */
public enum AgentExecutionStatus {
  WAITING_APPROVAL,
  PENDING,
  RUNNING,
  WAITING_SKILL,
  WAITING_HUMAN,
  PAUSED,
  SUCCEEDED,
  FAILED,
  REJECTED,
  CANCELLED
}
