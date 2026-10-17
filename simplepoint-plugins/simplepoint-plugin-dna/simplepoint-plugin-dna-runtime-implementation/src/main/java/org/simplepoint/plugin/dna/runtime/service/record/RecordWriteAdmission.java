package org.simplepoint.plugin.dna.runtime.service.record;

/** Trusted tenant and selected policy version for a new target transaction; not part of its business fingerprint. */
record RecordWriteAdmission(String tenantId, long version, long resourceGeneration) {

  RecordWriteAdmission(String tenantId, long version) {
    this(tenantId, version, 0);
  }

  RecordWriteAdmission {
    if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255 || version < 0 || resourceGeneration < 0) {
      throw new IllegalArgumentException("A bounded tenant and write-policy version are required");
    }
  }
}
