package org.simplepoint.plugin.dna.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

class PhysicalSchemaValidatorTest {

  private final PhysicalSchemaValidator validator = new PhysicalSchemaValidator(
      new StandardLogicalValueConverter(new ObjectMapper())
  );

  @Test
  void acceptsCanonicalDefaultsAndRejectsEquivalentNonCanonicalValues() {
    validator.validate(schema(new LogicalValue(LogicalValueKind.DECIMAL, "12.30")));

    assertThatThrownBy(() -> validator.validate(
        schema(new LogicalValue(LogicalValueKind.DECIMAL, "12.3"))
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.SCHEMA_INVALID));
  }

  private static PhysicalSchemaDefinition schema(final LogicalValue defaultValue) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(new LogicalFieldDefinition(
            "field-1",
            "amount",
            "Amount",
            LogicalFieldType.DECIMAL,
            false,
            null,
            8,
            2,
            defaultValue
        )),
        List.of()
    );
  }
}
