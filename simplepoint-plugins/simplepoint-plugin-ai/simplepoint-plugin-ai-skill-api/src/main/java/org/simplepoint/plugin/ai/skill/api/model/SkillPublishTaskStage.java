package org.simplepoint.plugin.ai.skill.api.model;

/** Last durable checkpoint reached by a Skill publication task. */
public enum SkillPublishTaskStage {
  QUEUED,
  GENERATING,
  PUSHING,
  VERIFYING,
  CREATING_VERSION,
  ACTIVATING,
  COMPLETED
}
