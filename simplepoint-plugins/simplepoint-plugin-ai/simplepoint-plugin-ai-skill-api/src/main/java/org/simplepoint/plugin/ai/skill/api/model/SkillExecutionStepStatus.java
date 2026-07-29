package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Durable lifecycle for one declarative Skill workflow step.
 */
public enum SkillExecutionStepStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED
}
