package org.simplepoint.platform.dictionary.registration;

import java.util.UUID;

/** Durable completion identity; a broker or transport acknowledgement is not this receipt. */
public record DictionaryRegistrationReceipt(
    String moduleCode,
    String contributionKey,
    String version,
    UUID operationId,
    long generation,
    String fingerprint) {
  /** Rejects incomplete responses before a module can regard its initialization as complete. */
  public DictionaryRegistrationReceipt {
    if (moduleCode == null
        || moduleCode.isBlank()
        || contributionKey == null
        || contributionKey.isBlank()
        || version == null
        || version.isBlank()
        || operationId == null
        || generation < 1
        || fingerprint == null
        || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid dictionary registration receipt");
    }
  }
}
