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

  /** Stable, expression-safe identity independent of renamed storage columns (which are limited to 63 characters). */
  public static String field(final String fieldId) {
    if (fieldId == null || fieldId.isBlank()) {
      throw new IllegalArgumentException("fieldId is required");
    }
    try {
      return "field_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(fieldId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Reads stable grants and retains restrictive legacy column grants during migration. */
  public static String access(java.util.Map<String, String> permissions, String model, String fieldId, String legacyCode) {
    if (permissions == null) {
      return null;
    }
    String prefix = key(model) + "#";
    String stable = fieldId == null ? null : permissions.get(prefix + field(fieldId));
    String legacy = permissions.get(prefix + legacyCode);
    for (String level : java.util.List.of("HIDDEN", "MASKED", "VISIBLE", "EDITABLE")) {
      if (level.equals(stable) || level.equals(legacy)) {
        return level;
      }
    }
    return stable == null && legacy == null ? null : "HIDDEN";
  }
}
