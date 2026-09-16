package org.simplepoint.plugin.ai.core.api.model;

import java.time.Instant;

/**
 * Minimal dependency label returned to an Agent or Workflow editor.
 *
 * @param kind dependency kind
 * @param resourceId stable definition or model identifier
 * @param resourceCode human-readable resource code
 * @param resourceName human-readable resource name
 * @param resourceVersionId immutable version identifier, absent for models
 * @param resourceVersion immutable version name, absent for models
 * @param scopeType effective ownership scope
 * @param publishedAt publication time, absent for models
 * @param selectable whether a new selection may use the option
 * @param availabilityCode why a resolved historical selection is unavailable
 */
public record AiDependencyOption(
    AiDependencyKind kind,
    String resourceId,
    String resourceCode,
    String resourceName,
    String resourceVersionId,
    String resourceVersion,
    AiResourceScope scopeType,
    Instant publishedAt,
    boolean selectable,
    AiDependencyAvailabilityCode availabilityCode
) {
}
