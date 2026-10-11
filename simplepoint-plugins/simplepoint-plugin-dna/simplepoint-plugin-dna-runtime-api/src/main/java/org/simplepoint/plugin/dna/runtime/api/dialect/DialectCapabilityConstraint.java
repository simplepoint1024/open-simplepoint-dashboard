package org.simplepoint.plugin.dna.runtime.api.dialect;

import java.util.Objects;

/** Typed and machine-readable limitation on one operation. */
public record DialectCapabilityConstraint(
    CapabilityConstraintType type,
    CapabilityConstraintOperator operator,
    String value
) {

  /** Validates the typed constraint payload. */
  public DialectCapabilityConstraint {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(operator, "operator");
    if (value == null || value.isBlank() || value.length() > 512) {
      throw new IllegalArgumentException("Constraint value must contain 1 to 512 characters");
    }
    value = value.trim();
    if (type == CapabilityConstraintType.MAX_IDENTIFIER_LENGTH
        || type == CapabilityConstraintType.MAX_PARAMETER_COUNT) {
      try {
        if (Integer.parseInt(value) < 1) {
          throw new IllegalArgumentException("Numeric constraint must be positive");
        }
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Numeric constraint must be an integer", exception);
      }
    }
  }
}
