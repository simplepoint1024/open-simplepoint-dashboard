package org.simplepoint.plugin.ai.core.api.model;

/** Lifecycle status of one AI invocation. */
public enum AiInvocationStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
