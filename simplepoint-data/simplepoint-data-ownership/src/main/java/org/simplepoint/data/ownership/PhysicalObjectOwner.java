package org.simplepoint.data.ownership;

/** Logical ownership survives resource offline, tenant switches and deployment host changes. */
public record PhysicalObjectOwner(String moduleId, String tenantId, String resourceId) {
  /** Validates bounded, canonical owner identities without including them in error messages. */
  public PhysicalObjectOwner {
    moduleId = required(moduleId, 64);
    if (!moduleId.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("A logical module identity is required");
    }
    tenantId = tenantId == null ? null : required(tenantId, 255);
    resourceId = required(resourceId, 128);
  }

  static String required(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.codePointCount(0, value.length()) > maximum
        || value
            .codePoints()
            .anyMatch(
                code ->
                    Character.isISOControl(code)
                        || code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)) {
      throw new IllegalArgumentException("A bounded canonical ownership identity is required");
    }
    return value;
  }
}
