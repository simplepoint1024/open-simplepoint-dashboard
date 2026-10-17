package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class RecordSchemaCompatibilityTest {

  @Test
  void acceptsStableIdentityRenameWideningAndOptionalAdditions() {
    PhysicalSchemaDefinition requested = schema(
        stringField("field-title", "title", true, 64, null)
    );
    PhysicalSchemaDefinition active = schema(
        stringField("field-title", "subject", true, 128, null),
        stringField("field-notes", "notes", true, 256, null)
    );

    assertThatCode(() -> RecordSchemaCompatibility.requireCompatible(requested, active))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsRemovedRetypedAndNarrowedHistoricalFields() {
    LogicalFieldDefinition requested = stringField("field-title", "title", true, 128, null);

    assertIncompatible(schema(requested), schema());
    assertIncompatible(
        schema(requested),
        schema(new LogicalFieldDefinition(
            "field-title", "title", "Title", LogicalFieldType.TEXT,
            true, null, null, null, null
        ))
    );
    assertIncompatible(
        schema(requested),
        schema(stringField("field-title", "title", true, 64, null))
    );
  }

  @Test
  void requiresDefaultsWhenOldCreatesCouldOmitAnActiveRequiredField() {
    PhysicalSchemaDefinition requested = schema(
        stringField("field-title", "title", true, 128, null)
    );
    assertIncompatible(
        requested,
        schema(stringField("field-title", "title", false, 128, null))
    );
    assertIncompatible(
        requested,
        schema(
            stringField("field-title", "title", true, 128, null),
            stringField("field-owner", "owner", false, 64, null)
        )
    );

    assertThatCode(() -> RecordSchemaCompatibility.requireCompatible(
        requested,
        schema(
            stringField("field-title", "title", true, 128, null),
            stringField("field-owner", "owner", false, 64, "system")
        )
    )).doesNotThrowAnyException();
  }

  private static void assertIncompatible(
      final PhysicalSchemaDefinition requested,
      final PhysicalSchemaDefinition active
  ) {
    assertThatThrownBy(() -> RecordSchemaCompatibility.requireCompatible(requested, active))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
  }

  private static PhysicalSchemaDefinition schema(final LogicalFieldDefinition... fields) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(fields),
        List.of()
    );
  }

  private static LogicalFieldDefinition stringField(
      final String fieldId,
      final String code,
      final boolean nullable,
      final int length,
      final String defaultValue
  ) {
    return new LogicalFieldDefinition(
        fieldId,
        code,
        "Title",
        LogicalFieldType.STRING,
        nullable,
        length,
        null,
        null,
        defaultValue == null
            ? null : new LogicalValue(LogicalValueKind.STRING, defaultValue)
    );
  }
}
