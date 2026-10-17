package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Target-confirmed resolution of a cancellation request. Both outcomes consume the original
 * target version: cancellation preserves the old policy, while an already committed publication
 * must finish activation. Neither outcome allows the caller to keep sending the previous version.
 */
public record RecordWritePolicyCancellationView(
    boolean cancelled,
    long version,
    String publicationFingerprint,
    String targetFingerprint
) {

  /** Requires distinct publication/cancellation identities and a confirmed positive target version. */
  public RecordWritePolicyCancellationView {
    if (version < 1 || publicationFingerprint == null || !publicationFingerprint.matches("[a-f0-9]{64}")
        || targetFingerprint == null || !targetFingerprint.matches("[a-f0-9]{64}")
        || cancelled == publicationFingerprint.equals(targetFingerprint)) {
      throw new IllegalArgumentException("Invalid policy cancellation resolution");
    }
  }
}
