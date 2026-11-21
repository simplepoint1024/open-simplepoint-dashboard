package org.simplepoint.plugin.dna.runtime.service.support;

import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

/** Runtime compatibility facade; live APP views and committed copies use the same conversion. */
public final class ReferenceMappingConversions {
  private ReferenceMappingConversions() {}

  /** Converts an already authorized value under the declared exact type and null contract. */
  public static LogicalValue convert(
      RecordReferenceConversion conversion,
      LogicalFieldDefinition source,
      LogicalFieldDefinition destination,
      Object value) {
    return org.simplepoint.plugin.dna.runtime.api.schema.ReferenceMappingConversions.convert(
        conversion, source, destination, value);
  }
}
