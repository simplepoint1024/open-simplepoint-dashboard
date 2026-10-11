package org.simplepoint.plugin.dna.runtime.service.schema;

import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;

/** Internal trusted publication target resolved from the physical model binding. */
public record PhysicalSchemaTarget(
    String physicalModelId,
    RuntimeMetadataTarget metadataTarget
) {

  /** Validates trusted target identity. */
  public PhysicalSchemaTarget {
    if (physicalModelId == null || physicalModelId.isBlank()) {
      throw new IllegalArgumentException("physicalModelId is required");
    }
    if (metadataTarget == null) {
      throw new IllegalArgumentException("metadataTarget is required");
    }
  }
}
