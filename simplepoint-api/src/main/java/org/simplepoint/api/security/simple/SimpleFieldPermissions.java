package org.simplepoint.api.security.simple;

/**
 * Record representing field permissions.
 *
 * @param authority the authority of the permission
 * @param resource  the resource associated with the permission
 * @param action    the action allowed by the permission
 */
public record SimpleFieldPermissions(String authority, String resource, String action) {
  /**
   * Extracts and returns the field name from the resource string.
   * The resource string is expected to be in the format "ClassName#FieldName".
   *
   * @return the field name if present; otherwise, null
   */
  public String getFieldName() {
    if (resource != null && resource.contains("#")) {
      return resource.split("#")[1];
    }
    return null;
  }
}
