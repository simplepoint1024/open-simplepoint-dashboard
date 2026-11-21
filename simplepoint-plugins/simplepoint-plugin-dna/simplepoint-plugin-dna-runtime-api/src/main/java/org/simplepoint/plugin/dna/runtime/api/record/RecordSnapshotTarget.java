package org.simplepoint.plugin.dna.runtime.api.record;

/** Server-owned original permission entrance; contains no row identity, value or granted access. */
public record RecordSnapshotTarget(
    String modelId, String appId, String formId, String resourceCode) {
  /** Bounded metadata used only by the trusted owner to collect the current viewer's policies. */
  public RecordSnapshotTarget {
    modelId = RecordQueryLimits.modelId(modelId);
    appId = RecordQueryLimits.modelId(appId);
    formId = RecordQueryLimits.modelId(formId);
    if (resourceCode == null
        || resourceCode.isBlank()
        || resourceCode.length() > 200
        || resourceCode.indexOf('\0') >= 0
        || !resourceCode.equals(resourceCode.trim())) {
      throw new IllegalArgumentException("Invalid snapshot permission entrance");
    }
  }
}
