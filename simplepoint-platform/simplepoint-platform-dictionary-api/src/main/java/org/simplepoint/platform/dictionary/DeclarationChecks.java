package org.simplepoint.platform.dictionary;

import java.util.List;

/** Bounded declaration validation, independent of transport, persistence and display locale. */
final class DeclarationChecks {
  private DeclarationChecks() {}

  static void text(String value, String field, int maximum, boolean required) {
    if (value == null) {
      if (required) {
        throw invalid(field);
      }
      return;
    }
    if ((required && value.isBlank())
        || value.codePointCount(0, value.length()) > maximum
        || value
            .codePoints()
            .anyMatch(c -> c == 0 || Character.getType(c) == Character.SURROGATE)) {
      throw invalid(field);
    }
  }

  static void identifier(String value, String field, int maximum) {
    text(value, field, maximum, true);
    if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._:+-]*")) {
      throw invalid(field);
    }
  }

  static <T> List<T> list(List<T> values, String field, int minimum, int maximum) {
    if (values == null
        || values.size() < minimum
        || values.size() > maximum
        || values.stream().anyMatch(java.util.Objects::isNull)) {
      throw invalid(field);
    }
    return List.copyOf(values);
  }

  static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("INVALID_DICTIONARY_DECLARATION: " + field);
  }
}
