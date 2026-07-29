package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Durable Skill workflow execution lifecycle.
 */
public enum SkillExecutionStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
