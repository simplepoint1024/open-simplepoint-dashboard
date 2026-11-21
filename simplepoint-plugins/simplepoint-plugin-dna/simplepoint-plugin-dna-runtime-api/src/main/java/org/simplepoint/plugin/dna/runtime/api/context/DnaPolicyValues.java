package org.simplepoint.plugin.dna.runtime.api.context;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared canonical bounds for source and explicitly propagated related-resource policies. */
final class DnaPolicyValues {
  private DnaPolicyValues() {}

  static final Set<String> DATA_SCOPES =
      Set.of("ALL", "SELF", "DEPT", "DEPT_AND_BELOW", "CUSTOM", "DENY");
  static final Set<String> FIELD_ACCESS = Set.of("EDITABLE", "VISIBLE", "MASKED", "HIDDEN");

  static Values policy(
      String scope,
      Set<String> departments,
      List<String> expressions,
      Map<String, String> fields,
      boolean requireScope) {
    scope = optional(scope, "dataScopeType", 32);
    scope = scope == null ? null : scope.toUpperCase(Locale.ROOT);
    if (scope == null && requireScope || scope != null && !DATA_SCOPES.contains(scope)) {
      throw new IllegalArgumentException("dataScopeType is invalid");
    }
    departments = normalizeSet(departments);
    expressions = normalizeList(expressions);
    fields = normalizePermissions(fields);
    validateStrings(departments, "departmentIds", 1000, 255);
    validateStrings(expressions, "dataScopeExpressions", 32, 4000);
    if (fields.size() > 512
        || fields.entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getKey().length() > 200
                        || !FIELD_ACCESS.contains(entry.getValue()))) {
      throw new IllegalArgumentException("fieldPermissions is invalid");
    }
    return new Values(scope, departments, expressions, fields);
  }

  record Values(
      String scope,
      Set<String> departments,
      List<String> expressions,
      Map<String, String> fields) {}

  static String required(final String value, final String name, final int maximum) {
    String normalized = optional(value, name, maximum);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  static String optional(final String value, final String name, final int maximum) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }

  static void validateStrings(
      final Collection<String> values,
      final String name,
      final int maximumSize,
      final int maximumLength) {
    if (values.size() > maximumSize
        || values.stream()
            .anyMatch(
                value -> value == null || value.isBlank() || value.length() > maximumLength)) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  static Set<String> normalizeSet(final Set<String> values) {
    if (values == null) {
      return Set.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    values.forEach(value -> normalized.add(value == null ? null : value.trim()));
    return java.util.Collections.unmodifiableSet(normalized);
  }

  static List<String> normalizeList(final List<String> values) {
    if (values == null) {
      return List.of();
    }
    return java.util.Collections.unmodifiableList(
        values.stream().map(value -> value == null ? null : value.trim()).toList());
  }

  static Map<String, String> normalizePermissions(final Map<String, String> values) {
    if (values == null) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (key == null || value == null) {
            throw new IllegalArgumentException("fieldPermissions is invalid");
          }
          if (normalized.putIfAbsent(key.trim(), value.trim().toUpperCase(Locale.ROOT)) != null) {
            throw new IllegalArgumentException("fieldPermissions contains ambiguous identities");
          }
        });
    return Map.copyOf(normalized);
  }
}
