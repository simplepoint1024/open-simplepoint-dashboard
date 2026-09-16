package org.simplepoint.plugin.ai.core.api.repository;

/**
 * Projection for resolving current and historical dependency selections.
 */
public interface AiDependencyResolutionView
    extends AiDependencyOptionView {

  /**
   * Returns whether a new selection may use this option.
   *
   * @return whether a new selection may use this option
   */
  boolean getSelectable();

  /**
   * Returns the bounded unavailability code.
   *
   * @return bounded unavailability code, or {@code null} when selectable
   */
  String getAvailabilityCode();
}
