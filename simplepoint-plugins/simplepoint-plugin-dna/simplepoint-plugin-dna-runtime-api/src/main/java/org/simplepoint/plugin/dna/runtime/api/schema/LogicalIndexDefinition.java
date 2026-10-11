package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Database-neutral index declaration referencing logical field codes. */
public record LogicalIndexDefinition(
    String code,
    List<String> fieldCodes,
    boolean unique
) {

  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  /** Validates a portable index name and its ordered field references. */
  public LogicalIndexDefinition {
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Index code has an invalid format");
    }
    fieldCodes = fieldCodes == null ? List.of() : List.copyOf(fieldCodes);
    if (fieldCodes.isEmpty()) {
      throw new IllegalArgumentException("An index requires at least one field");
    }
    if (fieldCodes.stream().anyMatch(value -> value == null || !CODE.matcher(value).matches())) {
      throw new IllegalArgumentException("Index field code has an invalid format");
    }
    if (new HashSet<>(fieldCodes).size() != fieldCodes.size()) {
      throw new IllegalArgumentException("An index cannot repeat a field");
    }
  }
}
