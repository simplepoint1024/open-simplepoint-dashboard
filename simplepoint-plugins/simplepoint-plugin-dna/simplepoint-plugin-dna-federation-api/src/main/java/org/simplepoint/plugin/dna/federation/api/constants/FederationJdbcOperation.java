package org.simplepoint.plugin.dna.federation.api.constants;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Operation permissions that can be granted to a JDBC connection user.
 */
public enum FederationJdbcOperation {
  METADATA,
  QUERY,
  EXPLAIN,
  DDL,
  DML;

  /**
   * Resolves an operation from a configured code.
   *
   * @param code configured code
   * @return resolved operation
   */
  public static FederationJdbcOperation fromCode(final String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("JDBC 操作权限不能为空");
    }
    return Arrays.stream(values())
        .filter(value -> value.name().equals(code.trim().toUpperCase(Locale.ROOT)))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("不支持的 JDBC 操作权限: " + code));
  }

  /**
   * Normalizes configured operation codes.
   *
   * @param values configured values
   * @return normalized codes
   */
  public static Set<String> normalizeCodes(final Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(FederationJdbcOperation::fromCode)
        .map(FederationJdbcOperation::name)
        .forEach(normalized::add);
    return Set.copyOf(normalized);
  }

  /**
   * Returns the default read-only JDBC grant set.
   *
   * @return default grant codes
   */
  public static Set<String> readOnlyDefaults() {
    return Set.of(METADATA.name(), QUERY.name());
  }
}
