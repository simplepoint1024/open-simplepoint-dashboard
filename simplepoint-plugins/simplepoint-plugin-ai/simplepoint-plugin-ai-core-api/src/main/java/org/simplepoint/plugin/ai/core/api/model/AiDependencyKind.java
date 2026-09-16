package org.simplepoint.plugin.ai.core.api.model;

/**
 * Resource kinds exposed by consumer-bound dependency directories.
 */
public enum AiDependencyKind {
  /**
   * An invokable language or multimodal model.
   */
  MODEL,

  /**
   * A published Agent version.
   */
  AGENT,

  /**
   * A published Skill version.
   */
  SKILL
}
