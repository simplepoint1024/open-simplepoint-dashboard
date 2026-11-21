package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.Objects;
import java.util.UUID;
import org.simplepoint.data.ownership.PhysicalObjectOwner;

/** Immutable control-store expectation, not a replacement for executing-session verification. */
public record PhysicalModelOwnership(String namespaceId, String tenantId) {
  /** Requires canonical persisted namespace and tenant identities. */
  public PhysicalModelOwnership {
    if (namespaceId == null || !UUID.fromString(namespaceId).toString().equals(namespaceId)) {
      throw new IllegalArgumentException("A canonical physical namespace identity is required");
    }
    Objects.requireNonNull(tenantId, "tenantId");
    new PhysicalObjectOwner("dna-runtime", tenantId, "identity-validation");
  }

  /** Builds the expected logical owner without depending on a deployment host or datasource ID. */
  public PhysicalObjectOwner owner(String modelId) {
    return new PhysicalObjectOwner("dna-runtime", tenantId, modelId);
  }
}
