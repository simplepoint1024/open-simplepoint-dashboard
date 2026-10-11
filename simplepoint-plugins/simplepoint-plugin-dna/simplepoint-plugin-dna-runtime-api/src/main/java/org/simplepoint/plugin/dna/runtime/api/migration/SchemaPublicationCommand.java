package org.simplepoint.plugin.dna.runtime.api.migration;

/** Optimistic command for publishing one immutable physical model version. */
public record SchemaPublicationCommand(
    int targetVersion,
    long expectedLockVersion
) {

  /** Validates the target and optimistic model version. */
  public SchemaPublicationCommand {
    if (targetVersion < 1) {
      throw new IllegalArgumentException("targetVersion must be positive");
    }
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion cannot be negative");
    }
  }
}
