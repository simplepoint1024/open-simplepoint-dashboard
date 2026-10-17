package org.simplepoint.plugin.workflow.api.model;

import java.util.Set;

/** Bounded effective user authority supplied only by an allowlisted platform service. */
public record WorkflowInvocationContext(
    String tenantId,
    String actorId,
    boolean administrator,
    Set<String> resources,
    Set<String> roles) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public WorkflowInvocationContext {
    tenantId = identity(tenantId, 64);
    actorId = identity(actorId, 64);
    resources = bounded(resources, 512, 200);
    roles = bounded(roles, 256, 100);
  }

  private static String identity(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.length() > maximum) {
      throw new IllegalArgumentException("Invalid workflow invocation identity");
    }
    return value;
  }

  private static Set<String> bounded(Set<String> values, int maximumSize, int maximumLength) {
    if (values == null) {
      return Set.of();
    }
    if (values.size() > maximumSize) {
      throw new IllegalArgumentException("Workflow authority budget exceeded");
    }
    values.forEach(value -> identity(value, maximumLength));
    return Set.copyOf(values);
  }
}
