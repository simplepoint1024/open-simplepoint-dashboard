package org.simplepoint.plugin.dna.runtime.service.installation;

import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;

/** Original removed bindings authenticated by reviewed cleanup evidence, never model history. */
public record PhysicalRetiredCleanupPlan(
    UUID operation, String evidenceChecksum, PhysicalBindingInventory inventory) {
  public PhysicalRetiredCleanupPlan {
    if (operation == null
        || evidenceChecksum == null
        || !evidenceChecksum.matches("[0-9a-f]{64}")
        || inventory == null
        || inventory.bindings().isEmpty()
        || inventory.bindings().stream()
            .anyMatch(binding -> !evidenceChecksum.equals(binding.cleanupEvidence()))) {
      throw new IllegalArgumentException("Invalid retired cleanup plan");
    }
  }

  /** Includes original physical identities, current tombstones and the source evidence checksum. */
  public String fingerprint() {
    return PhysicalOwnershipModelReview.digest(
        "dna-retired-cleanup-v1\n"
            + operation
            + "\n"
            + evidenceChecksum
            + "\n"
            + inventory.fingerprint());
  }
}
