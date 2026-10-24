package org.simplepoint.plugin.dna.runtime.service.support;

import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueConverter;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.springframework.stereotype.Component;

/** Applies the same strict value protocol to schema defaults before persistence. */
@Component
public class PhysicalSchemaValidator {

  private final LogicalValueConverter valueConverter;

  /** Creates a validator backed by the platform logical value converter. */
  public PhysicalSchemaValidator(final LogicalValueConverter valueConverter) {
    this.valueConverter = valueConverter;
  }

  /** Rejects defaults that are invalid or are not already in canonical form. */
  public void validate(final PhysicalSchemaDefinition schema) {
    if (schema == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_INVALID,
          "Physical schema is required"
      );
    }
    for (LogicalFieldDefinition field : schema.fields()) {
      LogicalValue declared = field.defaultValue();
      if (declared == null) {
        continue;
      }
      try {
        LogicalValue normalized = valueConverter.normalize(field, LogicalValueInputs.fromCanonical(declared));
        if (!declared.equals(normalized)) {
          throw new IllegalArgumentException("Default value is not canonical");
        }
      } catch (RuntimeException exception) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.SCHEMA_INVALID,
            "Default value is invalid for field " + field.code(),
            exception
        );
      }
    }
  }

}
