package org.simplepoint.plugin.dna.runtime.api.dialect;

/** Product metadata used to select a version-aware capability provider. */
public record DatabaseIdentity(
    String productName,
    String productVersion,
    int majorVersion,
    int minorVersion
) {

  /** Normalizes and validates database product metadata. */
  public DatabaseIdentity {
    if (productName == null || productName.isBlank()) {
      throw new IllegalArgumentException("productName is required");
    }
    if (productVersion == null || productVersion.isBlank()) {
      throw new IllegalArgumentException("productVersion is required");
    }
    if (majorVersion < 0 || minorVersion < 0) {
      throw new IllegalArgumentException("Database versions cannot be negative");
    }
    productName = productName.trim();
    productVersion = productVersion.trim();
  }
}
