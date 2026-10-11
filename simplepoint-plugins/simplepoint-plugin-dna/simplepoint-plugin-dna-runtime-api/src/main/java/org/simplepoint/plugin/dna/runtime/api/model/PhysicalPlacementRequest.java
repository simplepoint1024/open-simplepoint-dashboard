package org.simplepoint.plugin.dna.runtime.api.model;

import java.util.Objects;

/** Trusted server context used to select a physical placement. */
public record PhysicalPlacementRequest(
    String tenantId,
    String appId,
    String formId,
    String placementKey
) {

  /** Requires all trusted placement dimensions. */
  public PhysicalPlacementRequest {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(appId, "appId");
    Objects.requireNonNull(formId, "formId");
    Objects.requireNonNull(placementKey, "placementKey");
  }
}
