package org.simplepoint.plugin.ai.agent.api.model;

/**
 * Lifecycle of one model or Skill trace inside an Agent execution.
 */
public enum AgentTraceStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
