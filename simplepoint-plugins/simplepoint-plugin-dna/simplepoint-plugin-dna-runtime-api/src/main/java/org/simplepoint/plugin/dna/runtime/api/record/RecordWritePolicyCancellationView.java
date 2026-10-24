package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Target-confirmed resolution of a cancellation request. Both terminal outcomes consume the original
 * target version: cancellation preserves the old policy, while an already committed publication
 * must finish activation. Neither terminal outcome allows the caller to keep sending the previous
 * version. A non-null progress describes unfinished restoration at the expected version, with no
 * target fingerprint; it must never be interpreted as an already committed publication.
 */
public record RecordWritePolicyCancellationView(
    boolean cancelled,
    long version,
    String publicationFingerprint,
    String targetFingerprint,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    RecordWritePolicyProgress progress
) {

  /** Separates an unfinished restoration from either target-confirmed terminal resolution. */
  public RecordWritePolicyCancellationView {
    RecordQueryLimits.writePolicyVersion(version);
    boolean invalidResolution = progress == null
        ? version < 1 || targetFingerprint == null || !targetFingerprint.matches("[a-f0-9]{64}")
            || cancelled == java.util.Objects.equals(publicationFingerprint, targetFingerprint)
        : cancelled || targetFingerprint != null || progress.phase() != RecordWritePolicyPhase.RESTORING;
    if (publicationFingerprint == null || !publicationFingerprint.matches("[a-f0-9]{64}") || invalidResolution) {
      throw new IllegalArgumentException("Invalid policy cancellation resolution");
    }
  }

  /** Retains the terminal-only legacy response shape and constructor. */
  public RecordWritePolicyCancellationView(final boolean cancelled, final long version,
      final String publicationFingerprint, final String targetFingerprint) {
    this(cancelled, version, publicationFingerprint, targetFingerprint, null);
  }
}
