package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

class RecordCalculationCompilerTest {
  private final PhysicalSchemaCodec schemas = new PhysicalSchemaCodec(new ObjectMapper());
  private final RecordCalculationCompiler compiler = new RecordCalculationCompiler(schemas,
      Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
  private final RuntimeRecordModel parent = model("parent-model", "app", "source", "parent_table", parentSchema("total"));
  private final RuntimeRecordModel child = model("child-model", "app", "source", "child_table", childSchema("parent-model"));

  @Test
  void resolvesStableFieldsAndCompilesTransitiveMainDetailDependencies() {
    var compiled = compile(Map.of("total", "SUM([lines.amount])"), Map.of("amount", "[price]*[quantity]"));
    assertThat(compiled.plan().dependencies().get("parent.total"))
        .contains("lines.amount", "lines.price", "lines.quantity");
    var result = compiled.plan().evaluate(Map.of(), Map.of("lines", List.of(
        Map.of("price", new BigDecimal("28.53"), "quantity", 1),
        Map.of("price", new BigDecimal("15.68"), "quantity", 1))));
    assertThat((BigDecimal) result.parent().get("total")).isEqualByComparingTo("44.21");
    assertThat(compiled.fields().get("lines")).doesNotContainKeys("parent_ref", "order_field");
  }

  @Test
  void resolvesRenamedStorageByIdentityAndDoesNotPermitStorageCodeReferences() {
    var oldSchema = parentSchema("old_total");
    var renamed = new RuntimeRecordModel("parent-model", "app", "form", 2,
        parentSchema("renamed_total"), 1, oldSchema, parent.target());
    var definition = definition(Map.of("total", "SUM([lines.amount])"), Map.of(), child);
    var result = compiler.compile(renamed, definition, Map.of("lines", child), identity());
    assertThat(result.fields().get("parent").get("total").code()).isEqualTo("renamed_total");
    assertInvalid(() -> compiler.compile(renamed,
        definition(Map.of("total", "[renamed_total]"), Map.of(), child), Map.of("lines", child), identity()));
  }

  @Test
  void rejectsHiddenCyclesUnknownTargetsAndSystemReferencesEvenWithoutRows() {
    assertInvalid(() -> compile(Map.of("total", "IF(FALSE,SUM([lines.amount]),0)"), Map.of("amount", "[parent.total]")));
    assertInvalid(() -> compile(Map.of("absent", "1"), Map.of()));
    assertInvalid(() -> compile(Map.of("total", "SUM([lines.parent_ref])"), Map.of()));
    assertInvalid(() -> compile(Map.of("total", "SUM([lines.amount])"), Map.of("order_field", "1")));
    assertInvalid(() -> compile(Map.of("total", "[created_by]"), Map.of()));
  }

  @Test
  void rejectsForeignModelsTargetsSchemasAndMissingScopes() {
    var definition = definition(Map.of("total", "SUM([lines.amount])"), Map.of(), child);
    assertInvalid(() -> compiler.compile(parent, definition, Map.of(), identity()));
    assertInvalid(() -> compiler.compile(parent, definition,
        Map.of("lines", child, "extra", child), identity()));
    for (var other : List.of(
        model("other-model", "app", "source", "child_table", childSchema("parent-model")),
        model("child-model", "other-app", "source", "child_table", childSchema("parent-model")),
        model("child-model", "app", "other-source", "child_table", childSchema("parent-model")),
        model("child-model", "app", "source", "parent_table", childSchema("parent-model")),
        model("child-model", "app", "source", "child_table", childSchema("other-parent")))) {
      assertInvalid(() -> compiler.compile(parent, definition, Map.of("lines", other), identity()));
    }
    var wrongChecksum = new RecordCalculationDefinition("1", Map.of(), Map.of("lines",
        new RecordCalculationScope("child-model", new RecordSchemaReference(1, "f".repeat(64)), Map.of())));
    assertInvalid(() -> compiler.compile(parent, wrongChecksum, Map.of("lines", child), identity()));
    var wrongVersion = new RecordCalculationDefinition("1", Map.of(), Map.of("lines",
        new RecordCalculationScope("child-model", new RecordSchemaReference(2,
            schemas.encode(child.requestedSchema()).checksum()), Map.of())));
    assertInvalid(() -> compiler.compile(parent, wrongVersion, Map.of("lines", child), identity()));
  }

  @Test
  void validatesOwnerAfterMatchingSchemaAndRejectsDefaultsOnComputedTargets() {
    var foreign = model("child-model", "app", "source", "child_table", childSchema("other-parent"));
    assertInvalid(() -> compiler.compile(parent, definition(Map.of(), Map.of(), foreign), Map.of("lines", foreign), identity()));
    var defaultField = new LogicalFieldDefinition("total", "total", "Total", LogicalFieldType.DECIMAL,
        false, null, 18, 2, new LogicalValue(LogicalValueKind.DECIMAL, "0"));
    var defaultModel = model("parent-model", "app", "source", "parent_table",
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(defaultField), List.of()));
    assertInvalid(() -> compiler.compile(defaultModel, definition(Map.of("total", "1"), Map.of(), child),
        Map.of("lines", child), identity()));
    assertInvalid(() -> compiler.compile(child, new RecordCalculationDefinition("1", Map.of(), Map.of()), Map.of(), identity()));
  }

  @Test
  void requiresAndRunsTypedAdapterBeforeDependentFields() {
    var definition = definition(Map.of("total", "SUM([lines.amount])"), Map.of("amount", "[price]*[quantity]"), child);
    assertThatThrownBy(() -> compiler.compile(parent, definition, Map.of("lines", child), null))
        .isInstanceOf(NullPointerException.class);
    var result = compiler.compile(parent, definition, Map.of("lines", child), (field, value) ->
        "amount".equals(field.fieldId()) ? ((BigDecimal) value).setScale(2, java.math.RoundingMode.HALF_UP) : value);
    var values = result.plan().evaluate(Map.of(), Map.of("lines", List.of(Map.of("price", "1.235", "quantity", 1))));
    assertThat((BigDecimal) values.parent().get("total")).isEqualByComparingTo("1.24");
  }

  private RecordCalculationCompiler.Compiled compile(Map<String, String> main, Map<String, String> rows) {
    return compiler.compile(parent, definition(main, rows, child), Map.of("lines", child), identity());
  }

  private RecordCalculationDefinition definition(Map<String, String> main, Map<String, String> rows, RuntimeRecordModel detail) {
    return new RecordCalculationDefinition("1", main, Map.of("lines", new RecordCalculationScope(detail.physicalModelId(),
        new RecordSchemaReference(detail.requestedVersion(), schemas.encode(detail.requestedSchema()).checksum()), rows)));
  }

  private static java.util.function.BiFunction<LogicalFieldDefinition, Object, Object> identity() {
    // Only the graph/schema compiler is under test. Transaction execution must use physical validation.
    return (field, value) -> value;
  }

  private static RuntimeRecordModel model(String id, String app, String source, String table, PhysicalSchemaDefinition schema) {
    return new RuntimeRecordModel(id, app, id + "-form", 1, schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget(source, "", "public", table)));
  }

  private static PhysicalSchemaDefinition parentSchema(String code) {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(decimal("total", code)), List.of());
  }

  private static PhysicalSchemaDefinition childSchema(String parentId) {
    return new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        new LogicalFieldDefinition("parent_ref", "parent_id", "Owner", LogicalFieldType.UUID, false, null, null, null, null),
        new LogicalFieldDefinition("order_field", "row_order", "Order", LogicalFieldType.INT32, false, null, null, null, null),
        decimal("price", "unit_price"), decimal("quantity", "qty"), decimal("amount", "line_amount")),
        List.of(), new OwnedModelRelation(parentId, "parent_ref"));
  }

  private static LogicalFieldDefinition decimal(String id, String code) {
    return new LogicalFieldDefinition(id, code, id, LogicalFieldType.DECIMAL, true, null, 18, 2, null);
  }

  private static void assertInvalid(Runnable operation) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
  }
}
