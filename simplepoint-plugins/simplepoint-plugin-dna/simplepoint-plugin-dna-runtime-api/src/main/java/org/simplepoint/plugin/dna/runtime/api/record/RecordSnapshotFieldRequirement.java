package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Additional capture-time value requirements. They can only tighten a captured write and never
 * replace installed validation. maxTextUnits bounds a consumer's UTF-16 transport contract;
 * ordinary business text lengths continue to use Unicode code points.
 */
public record RecordSnapshotFieldRequirement(String fieldId, boolean required, Integer maxTextUnits,
    String pattern, boolean nonBlank, boolean trimmed, boolean noControls) {

  /** Keeps the neutral transport contract bounded; model and RE2 checks run inside DNA. */
  public RecordSnapshotFieldRequirement {
    RecordCalculationDefinition.identity(fieldId);
    if (maxTextUnits != null && (maxTextUnits < 1 || maxTextUnits > 16_384)
        || pattern != null && (pattern.isBlank() || pattern.length() > 512)) {
      throw new IllegalArgumentException("Invalid bounded snapshot field requirement");
    }
  }
}
