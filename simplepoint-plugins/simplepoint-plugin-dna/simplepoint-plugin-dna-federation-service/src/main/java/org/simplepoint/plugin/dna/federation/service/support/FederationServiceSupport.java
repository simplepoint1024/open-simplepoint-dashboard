package org.simplepoint.plugin.dna.federation.service.support;

import java.util.Map;
import org.simplepoint.api.base.BaseEntity;

/**
 * Shared normalization helpers for federation services.
 */
public final class FederationServiceSupport {

  private FederationServiceSupport() {
  }

  /**
   * Normalizes a text attribute to a like query when the caller only provides plain text.
   *
   * @param attributes filter attributes
   * @param key        attribute key
   */
  public static void normalizeLikeQuery(final Map<String, String> attributes, final String key) {
    String value = attributes.get(key);
    if (value != null && !value.contains(":")) {
      attributes.put(key, "like:" + value);
    }
  }

  /**
   * Requires a non-empty entity id.
   *
   * @param entity entity carrying the id
   * @return normalized id
   */
  public static String requireEntityId(final BaseEntity<String> entity) {
    if (entity == null) {
      throw new IllegalArgumentException("实体不能为空");
    }
    return requireValue(entity.getId(), "实体ID不能为空");
  }

  /**
   * Requires a non-empty string value.
   *
   * @param value   raw value
   * @param message error message
   * @return normalized value
   */
  public static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  /**
   * Trims a string and converts blanks to null.
   *
   * @param value raw value
   * @return normalized value
   */
  public static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
