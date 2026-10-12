package org.simplepoint.core.validation;

import java.util.Map;

/**
 * Safe, language-neutral field error. Rejected values and validator internals never leave the
 * server.
 */
public record FormViolation(
    String field, String keyword, Map<String, Object> params, String messageKey) {
  public FormViolation(String field, String keyword, Map<String, Object> params) {
    this(field, keyword, params, null);
  }
}
