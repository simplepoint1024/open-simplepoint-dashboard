package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;

/** Creates one record with server-owned identity, tenant, version, and audit fields. */
public record RecordCreateCommand(String physicalModelId, Map<String, Object> values) {

  /** Validates the model identity and dynamic field keys. */
  public RecordCreateCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    values = RecordValues.copy(values);
  }
}
