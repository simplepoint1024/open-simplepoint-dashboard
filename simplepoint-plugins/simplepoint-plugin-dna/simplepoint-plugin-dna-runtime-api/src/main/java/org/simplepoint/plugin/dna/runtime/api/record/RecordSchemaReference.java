package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Locale;

/** Immutable physical schema identity required by every record operation. */
public record RecordSchemaReference(int version, String checksum) {

  /** Validates and canonicalizes the published physical schema identity. */
  public RecordSchemaReference {
    if (version < 1) {
      throw new IllegalArgumentException("Record schema version must be positive");
    }
    if (checksum == null || !checksum.matches("[A-Fa-f0-9]{64}")) {
      throw new IllegalArgumentException("Record schema checksum must be a SHA-256 value");
    }
    checksum = checksum.toLowerCase(Locale.ROOT);
  }
}
