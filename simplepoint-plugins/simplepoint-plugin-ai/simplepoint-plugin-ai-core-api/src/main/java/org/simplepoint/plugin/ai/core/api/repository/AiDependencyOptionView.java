package org.simplepoint.plugin.ai.core.api.repository;

import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Minimal scalar projection used by dependency directory queries.
 */
public interface AiDependencyOptionView {

  /**
   * Returns the definition or model identifier.
   *
   * @return definition or model identifier
   */
  String getResourceId();

  /**
   * Returns the human-readable resource code.
   *
   * @return human-readable resource code
   */
  String getResourceCode();

  /**
   * Returns the human-readable resource name.
   *
   * @return human-readable resource name
   */
  String getResourceName();

  /**
   * Returns the immutable version identifier.
   *
   * @return immutable version identifier, or {@code null} for models
   */
  String getResourceVersionId();

  /**
   * Returns the immutable version name.
   *
   * @return immutable version name, or {@code null} for models
   */
  String getResourceVersion();

  /**
   * Returns the stored resource scope.
   *
   * @return stored resource scope
   */
  AiResourceScope getScopeType();

  /**
   * Returns the publication time.
   *
   * @return publication time, or {@code null} for models
   */
  Instant getPublishedAt();
}
