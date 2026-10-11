package org.simplepoint.plugin.dna.runtime.api.schema;

/** Converts an incoming record value to its canonical logical representation. */
public interface LogicalValueConverter {

  /**
   * Normalizes a value without applying database-specific type rules.
   *
   * @param field target logical field
   * @param rawValue JSON-decoded or JVM input value
   * @return canonical logical value
   */
  LogicalValue normalize(LogicalFieldDefinition field, Object rawValue);
}
