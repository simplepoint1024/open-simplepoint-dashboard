package org.simplepoint.plugin.rbac.resource.api.deployment;

import java.util.UUID;

/** Durable writer authority; retirement preserves history and does not unpublish business data. */
public record ResourceDeploymentState(
    String owner, long generation, UUID operationId, String clientId) {
  /** Whether this generation permits its exact authenticated client to register. */
  public boolean active() {
    return clientId != null;
  }
}
