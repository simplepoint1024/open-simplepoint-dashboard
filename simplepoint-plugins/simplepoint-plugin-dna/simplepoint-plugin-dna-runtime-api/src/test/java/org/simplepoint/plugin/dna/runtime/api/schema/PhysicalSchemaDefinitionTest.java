package org.simplepoint.plugin.dna.runtime.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;

class PhysicalSchemaDefinitionTest {

  @Test
  void validatesStableFieldsAndIndexReferences() {
    LogicalFieldDefinition name = field("field-name", "name");
    LogicalIndexDefinition index = new LogicalIndexDefinition(
        "idx_name",
        List.of("name"),
        false
    );

    PhysicalSchemaDefinition schema = new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(name),
        List.of(index)
    );

    assertThat(schema.fields()).containsExactly(name);
    assertThat(schema.indexes()).containsExactly(index);
  }

  @Test
  void rejectsSystemFieldsAndDuplicateStableIds() {
    assertThatThrownBy(() -> new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(field("field-id", "id")),
        List.of()
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("System field");

    assertThatThrownBy(() -> new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(field("same-id", "first_name"), field("same-id", "last_name")),
        List.of()
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate fieldId");
  }

  @Test
  void rejectsIndexesThatReferenceUnknownFields() {
    assertThatThrownBy(() -> new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(field("field-name", "name")),
        List.of(new LogicalIndexDefinition("idx_missing", List.of("missing"), false))
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown field");
  }

  private static LogicalFieldDefinition field(final String fieldId, final String code) {
    return new LogicalFieldDefinition(
        fieldId,
        code,
        "Field",
        LogicalFieldType.STRING,
        true,
        128,
        null,
        null,
        null
    );
  }
}
