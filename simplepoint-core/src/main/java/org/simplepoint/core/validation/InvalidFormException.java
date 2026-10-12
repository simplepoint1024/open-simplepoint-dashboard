package org.simplepoint.core.validation;

import java.util.List;

/**
 * Shared client-error contract for declarative validation, distinct from business/system failures.
 */
public final class InvalidFormException extends RuntimeException {
  private final List<FormViolation> violations;

  public InvalidFormException(List<FormViolation> violations) {
    super("Form validation failed");
    this.violations = List.copyOf(violations);
  }

  public List<FormViolation> getViolations() {
    return violations;
  }
}
