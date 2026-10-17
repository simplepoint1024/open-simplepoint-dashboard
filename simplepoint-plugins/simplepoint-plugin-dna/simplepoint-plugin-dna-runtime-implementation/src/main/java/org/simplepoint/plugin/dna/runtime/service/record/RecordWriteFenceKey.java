package org.simplepoint.plugin.dna.runtime.service.record;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable tenant/model admission identity, independent of application pages and schema versions. */
record RecordWriteFenceKey(String tenantId, String modelId) {

  RecordWriteFenceKey {
    tenantId = required(tenantId, 255);
    modelId = required(modelId, 64);
  }

  String id() {
    String seed = "record-write-fence-v1:" + tenantId.length() + ":" + tenantId + ":" + modelId.length() + ":" + modelId;
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String required(final String value, final int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum || !value.equals(value.trim())) {
      throw new IllegalArgumentException("A canonical tenant/model identity is required for the write fence");
    }
    return value;
  }
}
