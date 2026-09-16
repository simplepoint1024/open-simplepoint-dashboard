package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.Locale;

/**
 * Stable, language-neutral Runtime diagnostics exposed by public APIs.
 */
public enum AiRuntimeErrorCode {
  AI_RUNTIME_NODE_SHUT_DOWN,
  AI_RUNTIME_NODE_HEARTBEAT_EXPIRED,
  AI_RUNTIME_WORKLOAD_CANCELLATION_REQUESTED,
  AI_RUNTIME_PROFILE_REVISION_CHANGED,
  AI_RUNTIME_POOL_SCALE_DOWN_REQUESTED,
  AI_RUNTIME_WORKLOAD_DEADLINE_EXPIRED,
  AI_RUNTIME_WORKLOAD_NOT_OBSERVABLE,
  AI_RUNTIME_IMAGE_RESOLUTION_FAILED,
  AI_RUNTIME_OPERATION_FAILED;

  /**
   * Classifies an internal diagnostic without returning its original prose.
   *
   * @param diagnostic internal diagnostic text
   * @return stable public error code, or {@code null} when no error exists
   */
  public static AiRuntimeErrorCode fromDiagnostic(final String diagnostic) {
    if (diagnostic == null || diagnostic.isBlank()) {
      return null;
    }
    try {
      return valueOf(diagnostic.trim());
    } catch (IllegalArgumentException ignored) {
      // Legacy rows contain internal prose; classify those below.
    }
    String normalized = diagnostic.toLowerCase(Locale.ROOT);
    if (normalized.contains("shut down gracefully")) {
      return AI_RUNTIME_NODE_SHUT_DOWN;
    }
    if (normalized.contains("heartbeat lease expired")) {
      return AI_RUNTIME_NODE_HEARTBEAT_EXPIRED;
    }
    if (normalized.contains("cancellation requested")) {
      return AI_RUNTIME_WORKLOAD_CANCELLATION_REQUESTED;
    }
    if (normalized.contains("profile revision changed")) {
      return AI_RUNTIME_PROFILE_REVISION_CHANGED;
    }
    if (normalized.contains("pool scale-down requested")) {
      return AI_RUNTIME_POOL_SCALE_DOWN_REQUESTED;
    }
    if (normalized.contains("execution deadline expired")) {
      return AI_RUNTIME_WORKLOAD_DEADLINE_EXPIRED;
    }
    if (normalized.contains("state is not yet observable")) {
      return AI_RUNTIME_WORKLOAD_NOT_OBSERVABLE;
    }
    if (normalized.contains("image tag could not be resolved")
        || normalized.contains("image failed mcp admission")) {
      return AI_RUNTIME_IMAGE_RESOLUTION_FAILED;
    }
    return AI_RUNTIME_OPERATION_FAILED;
  }
}
