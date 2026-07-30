package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Durable Skill workflow execution lifecycle.
 */
public enum SkillExecutionStatus {
  WAITING_APPROVAL,
  PENDING,
  RUNNING,
  PAUSED,
  SUCCEEDED,
  FAILED,
  REJECTED,
  CANCELLED
}
