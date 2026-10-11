package org.simplepoint.plugin.dna.runtime.api.record;

/** Stable dynamic permission-model naming shared by DNA Runtime and APP publication. */
public final class RecordPermissionModel {

  private RecordPermissionModel() {
  }

  /** Returns the permission descriptor model key for one physical model. */
  public static String key(final String physicalModelId) {
    if (physicalModelId == null || physicalModelId.isBlank()) {
      throw new IllegalArgumentException("physicalModelId is required");
    }
    return "dna.runtime.record." + physicalModelId.trim();
  }
}
