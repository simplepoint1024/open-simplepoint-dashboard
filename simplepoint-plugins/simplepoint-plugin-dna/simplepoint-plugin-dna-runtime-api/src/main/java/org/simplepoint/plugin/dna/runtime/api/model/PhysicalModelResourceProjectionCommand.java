package org.simplepoint.plugin.dna.runtime.api.model;

/** Monotonic desired model for one server-owned resource; superseded generations cannot be replayed. */
public record PhysicalModelResourceProjectionCommand(
    String physicalModelId,
    String resourceCode,
    String ownerKey,
    long generation
) {
  /** Validates bounded identities and the positive owner-assigned generation. */
  public PhysicalModelResourceProjectionCommand {
    physicalModelId = required(physicalModelId, 36, "physicalModelId");
    resourceCode = required(resourceCode, 160, "resourceCode");
    ownerKey = required(ownerKey, 160, "ownerKey");
    if (generation < 1) {
      throw new IllegalArgumentException("Resource projection generation must be positive");
    }
  }

  private static String required(String value, int length, String name) {
    if (value == null || value.isBlank() || value.length() > length) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
