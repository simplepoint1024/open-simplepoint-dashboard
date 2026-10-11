package org.simplepoint.plugin.dna.runtime.api.model;

/** Server-side projection binding one page resource to a physical model. */
public record PhysicalModelResourceBindingCommand(
    String physicalModelId,
    String resourceCode,
    long expectedLockVersion
) {

  /** Validates identifiers and optimistic model version. */
  public PhysicalModelResourceBindingCommand {
    if (physicalModelId == null || physicalModelId.isBlank() || physicalModelId.length() > 64) {
      throw new IllegalArgumentException("physicalModelId is required");
    }
    if (resourceCode == null || resourceCode.isBlank() || resourceCode.length() > 160) {
      throw new IllegalArgumentException("resourceCode is required");
    }
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion cannot be negative");
    }
    physicalModelId = physicalModelId.trim();
    resourceCode = resourceCode.trim();
  }
}
