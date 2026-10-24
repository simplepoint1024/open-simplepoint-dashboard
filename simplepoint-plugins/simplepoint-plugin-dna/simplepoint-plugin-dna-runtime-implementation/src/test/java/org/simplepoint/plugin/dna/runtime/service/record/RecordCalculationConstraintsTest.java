package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.api.schema.*;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

class RecordCalculationConstraintsTest {
  private final PhysicalSchemaCodec schemas = new PhysicalSchemaCodec(new ObjectMapper());
  private final RecordCalculationCompiler compiler = new RecordCalculationCompiler(schemas, Clock.systemUTC());

  @Test
  void checksUnicodeLengthsAndRe2BeforeDependentExpressions() {
    var field = text("label");
    var rule = new RecordFieldConstraints(1, 1, "^😀$", List.of());
    assertThat(scalar(field, "LEFT(\"😀汉字\",1)", rule).evaluate(Map.of(), Map.of()).parent()).containsEntry("label", "😀");
    assertThatThrownBy(() -> scalar(field, "\"😀汉\"", rule).evaluate(Map.of(), Map.of())).isInstanceOf(DnaRuntimeException.class);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field, text("dependent")), List.of());
    var evaluator = new RecordCalculationEvaluator(compiler, model("root", schema), new RecordCalculationDefinition("1",
        Map.of("label", "\"invalid\"", "dependent", "IFERROR([label],\"😀\")"), Map.of(), Map.of("label", rule)), Map.of());
    assertThatThrownBy(() -> evaluator.evaluate(Map.of(), Map.of())).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void validatesOrdinaryInputsEvenWithoutAnyFormulaAndRetainsOptionalEmptySemantics() {
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of(), Map.of("input", new RecordFieldConstraints(2, 4, "^[0-9]+$", List.of())));
    assertThat(definition.isEmpty()).isFalse();
    var evaluator = new RecordCalculationEvaluator(compiler, model("root",
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(text("input")), List.of())), definition, Map.of());
    assertThat(evaluator.evaluate(Map.of("input", "12"), Map.of()).parent()).isEmpty();
    assertThat(evaluator.evaluate(Map.of("input", ""), Map.of()).parent()).isEmpty();
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("input", "abc"), Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("input", "1"), Map.of())).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void choicesUseExactPhysicalNumericTypesAndRejectSemanticDuplicates() {
    var field = new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, false, null, 24, 2, null);
    var rule = new RecordFieldConstraints(null, null, null, List.of(new LogicalValue(LogicalValueKind.DECIMAL, "9007199254740993.01")));
    assertThat(scalar(field, "9007199254740993.01", rule).evaluate(Map.of(), Map.of()).parent()).containsEntry("amount", "9007199254740993.01");
    assertThatThrownBy(() -> scalar(field, "9007199254740993.02", rule).evaluate(Map.of(), Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> scalar(field, "1", new RecordFieldConstraints(null, null, null, List.of(
        new LogicalValue(LogicalValueKind.DECIMAL, "1"), new LogicalValue(LogicalValueKind.DECIMAL, "1.00")))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> scalar(field, "1", new RecordFieldConstraints(null, null, null,
        List.of(new LogicalValue(LogicalValueKind.INTEGER, "1"))))).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsUnknownOrIncompatibleRulesAndBacktrackingOnlyPatternsBeforeEvaluation() {
    assertThatThrownBy(() -> scalar(text("label"), "\"a\"", new RecordFieldConstraints(null, null, "(?<=a)b", List.of())))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> scalar(text("label"), "\"a\"", new RecordFieldConstraints(129, null, null, List.of())))
        .isInstanceOf(DnaRuntimeException.class);
    var field = new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.INT32, false, null, null, null, null);
    assertThatThrownBy(() -> scalar(field, "1", new RecordFieldConstraints(1, null, null, List.of()))).isInstanceOf(DnaRuntimeException.class);
    var model = model("root", new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(text("label")), List.of()));
    assertThatThrownBy(() -> new RecordCalculationEvaluator(compiler, model, new RecordCalculationDefinition("1", Map.of(), Map.of(),
        Map.of("unknown", new RecordFieldConstraints(1, null, null, List.of()))), Map.of())).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void identicalFieldIdsInParentAndDetailsKeepIndependentConstraints() {
    var root = model("root", new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(text("label")), List.of()));
    var childSchema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(text("label"),
        new LogicalFieldDefinition("owner", "parent_record_id", "Owner", LogicalFieldType.UUID, false, null, null, null, null)),
        List.of(), new OwnedModelRelation("root", "owner"));
    var child = model("child", childSchema);
    var definition = new RecordCalculationDefinition("1", Map.of("label", "\"家\""), Map.of("lines",
        new RecordCalculationScope("child", new RecordSchemaReference(1, schemas.encode(childSchema).checksum()),
            Map.of("label", "\"明细\""), Map.of("label", new RecordFieldConstraints(2, 2, "^明细$", List.of())))),
        Map.of("label", new RecordFieldConstraints(1, 1, "^家$", List.of())));
    var result = new RecordCalculationEvaluator(compiler, root, definition, Map.of("lines", child))
        .evaluate(Map.of(), Map.of("lines", List.of(Map.of())));
    assertThat(result.parent()).containsEntry("label", "家");
    assertThat(result.details().get("lines")).containsExactly(Map.of("label", "明细"));
  }

  @Test
  void historicalMaximumCanBeWeakerThanTheNewPhysicalCapacityWithoutMakingInstallationInvalid() {
    var rule = new RecordFieldConstraints(null, 200, null, List.of());
    assertThat(scalar(text("label"), "\"中文😀\"", rule).evaluate(Map.of(), Map.of()).parent()).containsEntry("label", "中文😀");
    assertThatThrownBy(() -> scalar(text("label"), "\"" + "x".repeat(129) + "\"", rule).evaluate(Map.of(), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void calculatedAndOrdinaryJsonSelectionsShareBoundedDomainsBeforeDependentFormulas() {
    var field = new LogicalFieldDefinition("selection", "selection", "Selection", LogicalFieldType.JSON, true, null, null, null, null);
    var set = new RecordFieldConstraints(null, null, null, List.of(),
        new RecordJsonSelection(RecordJsonSelection.Mode.SET, List.of(List.of("一"), List.of("二"))));
    var path = new RecordFieldConstraints(null, null, null, List.of(),
        new RecordJsonSelection(RecordJsonSelection.Mode.PATH, List.of(List.of("中国", "上海"), List.of("日本", "東京"))));
    for (var entry : Map.of(set, "[\"二\",\"一\"]", path, "[\"日本\",\"東京\"]").entrySet()) {
      String formula = "\"" + entry.getValue().replace("\"", "\"\"") + "\"";
      assertThat(scalar(field, formula, entry.getKey()).evaluate(Map.of(), Map.of()).parent()).containsEntry("selection", entry.getValue());
      assertThat(scalar(field, "\"[]\"", entry.getKey()).evaluate(Map.of(), Map.of()).parent()).containsEntry("selection", "[]");
    }
    var model = model("root", new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field), List.of()));
    var ordinary = new RecordCalculationEvaluator(compiler, model,
        new RecordCalculationDefinition("1", Map.of(), Map.of(), Map.of("selection", set)), Map.of());
    assertThat(ordinary.evaluate(Map.of("selection", "[\"一\"]"), Map.of()).parent()).isEmpty();
    for (String invalid : List.of("[\"一\",\"一\"]", "[\"unknown\"]", "{}", "[1]", "\"一\"")) {
      assertThatThrownBy(() -> ordinary.evaluate(Map.of("selection", invalid), Map.of())).isInstanceOf(DnaRuntimeException.class);
    }
    var hierarchical = new RecordCalculationEvaluator(compiler, model,
        new RecordCalculationDefinition("1", Map.of(), Map.of(), Map.of("selection", path)), Map.of());
    for (String invalid : List.of("[\"中国\"]", "[\"上海\",\"中国\"]", "[\"中国\",\"東京\"]")) {
      assertThatThrownBy(() -> hierarchical.evaluate(Map.of("selection", invalid), Map.of())).isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(() -> scalar(text("label"), "\"[]\"", set)).isInstanceOf(DnaRuntimeException.class);
  }

  private RecordCalculationEvaluator scalar(LogicalFieldDefinition field, String formula, RecordFieldConstraints rule) {
    return new RecordCalculationEvaluator(compiler, model("root", new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT,
        List.of(field), List.of())), new RecordCalculationDefinition("1", Map.of(field.fieldId(), formula), Map.of(), Map.of(field.fieldId(), rule)), Map.of());
  }

  private static LogicalFieldDefinition text(String id) {
    return new LogicalFieldDefinition(id, id, id, LogicalFieldType.STRING, true, 128, null, null, null);
  }

  private static RuntimeRecordModel model(String id, PhysicalSchemaDefinition schema) {
    return new RuntimeRecordModel(id, "app", id, 1, schema, new PhysicalSchemaTarget(id,
        new RuntimeMetadataTarget("source", null, "public", id + "_records")));
  }
}
