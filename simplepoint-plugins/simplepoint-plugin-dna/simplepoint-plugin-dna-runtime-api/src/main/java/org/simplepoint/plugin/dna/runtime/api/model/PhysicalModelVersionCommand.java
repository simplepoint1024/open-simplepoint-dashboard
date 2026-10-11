package org.simplepoint.plugin.dna.runtime.api.model;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Creates the next immutable target schema version. */
public record PhysicalModelVersionCommand(
    long expectedLockVersion,
    PhysicalSchemaDefinition schema
) {

  /** Validates the optimistic lock and target schema. */
  public PhysicalModelVersionCommand {
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion cannot be negative");
    }
    Objects.requireNonNull(schema, "schema");
  }
}
