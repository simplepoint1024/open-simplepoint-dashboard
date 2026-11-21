package org.simplepoint.plugin.dna.runtime.service.schema;

import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;

/** Internal trusted publication target resolved from the physical model binding. */
public record PhysicalSchemaTarget(
    String physicalModelId,
    RuntimeMetadataTarget metadataTarget,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.Map<String, RuntimeMetadataTarget> referenceTargets,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        PhysicalModelOwnership ownership) {

  /** Creates a legacy target without inventing an ownership identity. */
  public PhysicalSchemaTarget(String physicalModelId, RuntimeMetadataTarget metadataTarget) {
    this(physicalModelId, metadataTarget, java.util.Map.of(), null);
  }

  /** Preserves legacy targets without synthesizing a namespace ownership certificate. */
  public PhysicalSchemaTarget(
      String physicalModelId,
      RuntimeMetadataTarget metadataTarget,
      java.util.Map<String, RuntimeMetadataTarget> referenceTargets) {
    this(physicalModelId, metadataTarget, referenceTargets, null);
  }

  /** Validates trusted target identity. */
  public PhysicalSchemaTarget {
    referenceTargets =
        referenceTargets == null
            ? java.util.Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(referenceTargets));
    if (physicalModelId == null || physicalModelId.isBlank()) {
      throw new IllegalArgumentException("physicalModelId is required");
    }
    if (metadataTarget == null) {
      throw new IllegalArgumentException("metadataTarget is required");
    }
  }

  /**
   * Binds the control-store expectation; absent legacy metadata remains absent in old snapshots.
   */
  public PhysicalSchemaTarget withOwnership(String namespaceId, String tenantId) {
    return new PhysicalSchemaTarget(
        physicalModelId,
        metadataTarget,
        referenceTargets,
        namespaceId == null ? null : new PhysicalModelOwnership(namespaceId, tenantId));
  }
}
