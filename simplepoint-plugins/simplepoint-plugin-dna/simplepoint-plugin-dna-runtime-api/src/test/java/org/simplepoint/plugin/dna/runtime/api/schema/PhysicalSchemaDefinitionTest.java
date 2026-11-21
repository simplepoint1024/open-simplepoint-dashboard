package org.simplepoint.plugin.dna.runtime.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;

class PhysicalSchemaDefinitionTest {

  @Test
  void optionalTenantIdentityPreservesLegacyJsonAndRoundTripsExplicitOptIn() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    String legacy = "{\"contractVersion\":\"v1\",\"fields\":[],\"indexes\":[]}";
    var original = mapper.readValue(legacy, PhysicalSchemaDefinition.class);
    assertThat(original.tenantRecordIdentity()).isNull();
    assertThat(mapper.writeValueAsString(original)).isEqualTo(legacy);
    var enabled = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of(), null, true);
    assertThat(mapper.readValue(mapper.writeValueAsString(enabled), PhysicalSchemaDefinition.class)).isEqualTo(enabled);
    assertThatThrownBy(() -> new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(),
        List.of(new LogicalIndexDefinition("tenant_key", List.of("tenant_id", "id"), true))))
        .isInstanceOf(IllegalArgumentException.class);
  }

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

  @Test
  void businessReferencesRoundTripUsingStableFieldsAndRejectInvalidSourceDeclarations() throws Exception {
    var field = new LogicalFieldDefinition("customer", "customer_id", "客户", LogicalFieldType.UUID,
        true, null, null, null, null);
    var reference = new LogicalReferenceDefinition("trade-customer", "customer", "customers", false);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field), List.of(), null, true, List.of(reference));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsString(schema), PhysicalSchemaDefinition.class)).isEqualTo(schema);
    assertThatThrownBy(() -> new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field), List.of(), null, true,
        List.of(reference, new LogicalReferenceDefinition("another", "customer", "customers", false))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field("customer", "customer_id")),
        List.of(), null, true, List.of(reference))).isInstanceOf(IllegalArgumentException.class);
    var fixed = new LogicalFieldDefinition("customer", "customer_id", "客户", LogicalFieldType.UUID,
        true, null, null, null, new LogicalValue(LogicalValueKind.UUID, "00000000-0000-0000-0000-000000000001"));
    assertThatThrownBy(() -> new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(fixed), List.of(), null, true,
        List.of(reference))).isInstanceOf(IllegalArgumentException.class);
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
