package org.simplepoint.plugin.dna.runtime.api.dialect;

import java.util.Set;

/** Explicit support declaration for one database operation. */
public record DialectCapability(
    CapabilitySupportLevel supportLevel,
    Set<DialectCapabilityConstraint> constraints
) {

  /** Requires explicit constraints whenever support is limited. */
  public DialectCapability {
    if (supportLevel == null) {
      throw new IllegalArgumentException("supportLevel is required");
    }
    constraints = constraints == null ? Set.of() : Set.copyOf(constraints);
    if (supportLevel == CapabilitySupportLevel.SUPPORTED_WITH_LIMITS
        && constraints.isEmpty()) {
      throw new IllegalArgumentException("Limited support requires machine-readable constraints");
    }
    if (supportLevel == CapabilitySupportLevel.SUPPORTED && !constraints.isEmpty()) {
      throw new IllegalArgumentException("Use SUPPORTED_WITH_LIMITS when constraints are present");
    }
  }

  /** Creates an unconditional support declaration. */
  public static DialectCapability supported() {
    return new DialectCapability(CapabilitySupportLevel.SUPPORTED, Set.of());
  }

  /** Creates an explicit unsupported declaration. */
  public static DialectCapability unsupported() {
    return new DialectCapability(CapabilitySupportLevel.UNSUPPORTED, Set.of());
  }
}
