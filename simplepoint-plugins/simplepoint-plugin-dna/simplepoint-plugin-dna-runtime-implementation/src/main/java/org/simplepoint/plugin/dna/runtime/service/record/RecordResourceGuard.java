package org.simplepoint.plugin.dna.runtime.service.record;

/**
 * Internal resource identity captured from trusted metadata and revalidated under a control lock.
 */
record RecordResourceGuard(String modelId, String resourceCode, long generation, boolean managed) {
  RecordResourceGuard {
    if (modelId == null
        || modelId.isBlank()
        || modelId.length() > 64
        || resourceCode == null
        || resourceCode.isBlank()
        || resourceCode.length() > 200
        || generation < 0) {
      throw new IllegalArgumentException("A bounded record resource identity is required");
    }
  }
}
