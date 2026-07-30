package org.simplepoint.plugin.ai.agent.api.model;

/**
 * Durable lifecycle of one Agent human-intervention task.
 */
public enum AgentHumanInterventionStatus {
  REQUESTED,
  WAITING,
  COMPLETED,
  EXPIRED,
  CANCELLED
}
