package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;
import java.util.UUID;

/** An incremental edge intent; -1 observes an edge that has never existed, not a retired edge. */
public record CollectionEdgeMutation(
    String targetRecordId, Long expectedTargetVersion, Long expectedEdgeVersion, Mode mode) {
  /** Unlink removes only the association; it never deletes either business record. */
  public enum Mode {
    LINK,
    UNLINK
  }

  /** Boxed required versions distinguish a missing JSON observation from valid version zero. */
  public CollectionEdgeMutation {
    targetRecordId = canonicalRecordId(targetRecordId);
    version(expectedTargetVersion, false);
    version(expectedEdgeVersion, true);
    Objects.requireNonNull(mode, "mode");
  }

  static String canonicalRecordId(String value) {
    Objects.requireNonNull(value, "saved record identity");
    String canonical = UUID.fromString(value).toString();
    if (!canonical.equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("A canonical saved record identity is required");
    }
    return canonical;
  }

  static void version(Long version, boolean absentAllowed) {
    if (version == null || version < (absentAllowed ? -1 : 0) || version == Long.MAX_VALUE) {
      throw new IllegalArgumentException("An explicit bounded collection version is required");
    }
  }
}
