package org.simplepoint.plugin.dna.runtime.api.context;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded tenant, actor, and effective page policy propagated by a trusted platform service. */
public record DnaInvocationContext(
    String tenantId,
    String actorId,
    String organizationId,
    String resourceCode,
    boolean administrator,
    Set<String> resources,
    String dataScopeType,
    Set<String> departmentIds,
    boolean dataScopeIncludeSelf,
    List<String> dataScopeExpressions,
    Map<String, String> fieldPermissions
) {

  private static final Set<String> DATA_SCOPES = Set.of(
      "ALL", "SELF", "DEPT", "DEPT_AND_BELOW", "CUSTOM", "DENY"
  );
  private static final Set<String> FIELD_ACCESS = Set.of(
      "EDITABLE", "VISIBLE", "MASKED", "HIDDEN"
  );

  /** Validates the service boundary and defensively copies all policy collections. */
  public DnaInvocationContext {
    tenantId = required(tenantId, "tenantId", 255);
    actorId = required(actorId, "actorId", 64);
    organizationId = optional(organizationId, "organizationId", 255);
    resourceCode = optional(resourceCode, "resourceCode", 200);
    dataScopeType = optional(dataScopeType, "dataScopeType", 32);
    dataScopeType = dataScopeType == null ? null : dataScopeType.toUpperCase(Locale.ROOT);
    resources = normalizeSet(resources);
    departmentIds = normalizeSet(departmentIds);
    dataScopeExpressions = normalizeList(dataScopeExpressions);
    fieldPermissions = normalizePermissions(fieldPermissions);
    validateStrings(resources, "resources", 512, 200);
    validateStrings(departmentIds, "departmentIds", 1_000, 255);
    validateStrings(dataScopeExpressions, "dataScopeExpressions", 32, 4_000);
    if (dataScopeType != null && !DATA_SCOPES.contains(dataScopeType)) {
      throw new IllegalArgumentException("dataScopeType is invalid");
    }
    if (fieldPermissions.size() > 512 || fieldPermissions.entrySet().stream().anyMatch(entry ->
        entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 200
            || !FIELD_ACCESS.contains(entry.getValue()))) {
      throw new IllegalArgumentException("fieldPermissions is invalid");
    }
  }

  private static String required(final String value, final String name, final int maximum) {
    String normalized = optional(value, name, maximum);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private static String optional(final String value, final String name, final int maximum) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }

  private static void validateStrings(
      final Collection<String> values,
      final String name,
      final int maximumSize,
      final int maximumLength
  ) {
    if (values.size() > maximumSize || values.stream().anyMatch(value ->
        value == null || value.isBlank() || value.length() > maximumLength)) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  private static Set<String> normalizeSet(final Set<String> values) {
    if (values == null) {
      return Set.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    values.forEach(value -> normalized.add(value == null ? null : value.trim()));
    return java.util.Collections.unmodifiableSet(normalized);
  }

  private static List<String> normalizeList(final List<String> values) {
    if (values == null) {
      return List.of();
    }
    return java.util.Collections.unmodifiableList(values.stream()
        .map(value -> value == null ? null : value.trim()).toList());
  }

  private static Map<String, String> normalizePermissions(final Map<String, String> values) {
    if (values == null) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    values.forEach((key, value) -> {
      if (key == null || value == null) {
        throw new IllegalArgumentException("fieldPermissions is invalid");
      }
      normalized.put(key.trim(), value.trim().toUpperCase(Locale.ROOT));
    });
    return Map.copyOf(normalized);
  }
}
