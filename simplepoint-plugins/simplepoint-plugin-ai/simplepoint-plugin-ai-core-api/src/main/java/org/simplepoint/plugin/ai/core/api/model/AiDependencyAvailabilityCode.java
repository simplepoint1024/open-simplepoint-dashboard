package org.simplepoint.plugin.ai.core.api.model;

/**
 * Bounded reasons why a previously selected dependency is no longer usable.
 */
public enum AiDependencyAvailabilityCode {
  /**
   * The dependency definition was disabled.
   */
  DEPENDENCY_DISABLED,

  /**
   * The model is unavailable or no longer has a supported model type.
   */
  MODEL_UNAVAILABLE,

  /**
   * The immutable dependency version was deprecated.
   */
  VERSION_DEPRECATED
}
