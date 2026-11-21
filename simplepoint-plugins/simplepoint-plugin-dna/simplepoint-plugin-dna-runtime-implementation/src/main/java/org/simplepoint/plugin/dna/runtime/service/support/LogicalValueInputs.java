package org.simplepoint.plugin.dna.runtime.service.support;

import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

/** Retains runtime compatibility while sharing exact scalar input interpretation across modules. */
public final class LogicalValueInputs {
  private LogicalValueInputs() {}

  /** Restores canonical values without converting numeric strings through floating point. */
  public static Object fromCanonical(LogicalValue value) {
    return org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueInputs.fromCanonical(value);
  }
}
