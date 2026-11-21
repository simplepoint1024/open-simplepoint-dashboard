package org.simplepoint.plugin.dna.runtime.service.support;

import org.simplepoint.plugin.dna.runtime.api.schema.CanonicalLogicalValueConverter;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueConverter;
import org.springframework.stereotype.Component;

/** Runtime bean for the shared pure canonical value contract. */
@Component
public class StandardLogicalValueConverter implements LogicalValueConverter {
  private final LogicalValueConverter values = new CanonicalLogicalValueConverter();

  @Override
  public LogicalValue normalize(LogicalFieldDefinition field, Object rawValue) {
    return values.normalize(field, rawValue);
  }
}
