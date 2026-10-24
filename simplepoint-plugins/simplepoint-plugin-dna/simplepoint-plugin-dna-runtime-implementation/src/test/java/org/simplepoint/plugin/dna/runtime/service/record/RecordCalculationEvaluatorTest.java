package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

class RecordCalculationEvaluatorTest {
  private final PhysicalSchemaCodec codec = new PhysicalSchemaCodec(new ObjectMapper());
  private final RecordCalculationCompiler compiler = new RecordCalculationCompiler(codec,
      Clock.fixed(Instant.parse("2026-10-17T10:00:00.123456789Z"), ZoneOffset.UTC));

  @Test
  void computesExactMainDetailValuesUsingStableIdsAndCurrentPhysicalColumnNames() {
    var evaluator = expense("ROUND([price]*[quantity]*[parent.rate],2)");
    var parent = Map.of("exchange_rate", "0.95", "total_amount", "999", "created_by", "actor");
    var rows = List.of(Map.of("unit_price", "10.01", "qty", "3", "line_amount", "999"),
        Map.of("unit_price", "8.25", "qty", "2", "line_amount", "999"));
    var result = evaluator.evaluate(parent, Map.of("lines", rows));
    assertThat(result.parent()).containsExactlyEntriesOf(Map.of("total_amount", "44.21"));
    assertThat(result.details().get("lines")).containsExactly(Map.of("line_amount", "28.53"), Map.of("line_amount", "15.68"));
    assertThat(parent).containsEntry("total_amount", "999");
    assertThat(rows.getFirst()).containsEntry("line_amount", "999");
    assertThat(evaluator.dependencies().get("parent.total")).contains("parent.rate", "lines.price", "lines.quantity");
  }

  @Test
  void preservesLargeExactDecimalsAndEmptyDetailSemantics() {
    var evaluator = expense("ROUND([price]*[quantity]*[parent.rate],2)");
    assertThat(evaluator.evaluate(Map.of("exchange_rate", "1"), Map.of("lines", List.of(
        Map.of("unit_price", "9007199254740993.01", "qty", 1)))).parent())
        .containsEntry("total_amount", "9007199254740993.01");
    assertThat(evaluator.evaluate(Map.of("exchange_rate", "1"), Map.of("lines", List.of())).parent())
        .containsEntry("total_amount", "0.00");
  }

  @Test
  void rejectsIncompleteUnboundedOrInexactPhysicalInputs() {
    var evaluator = expense("ROUND([price]*[quantity]*[parent.rate],2)");
    var validRow = Map.of("unit_price", "1.01", "qty", "1");
    assertThatThrownBy(() -> evaluator.evaluate(Map.of(), Map.of("lines", List.of(validRow))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", "1"), Map.of("lines", List.of(Map.of("qty", "1")))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", "1"), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", "1"),
        Map.of("lines", java.util.Collections.nCopies(41, validRow)))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", 0.95d), Map.of("lines", List.of(validRow))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", "1E-2147483647"), Map.of("lines", List.of(validRow))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsUnroundedProducerBeforeDownstreamIfErrorCanHideTheFailure() {
    var evaluator = expense("[price]*[quantity]*[parent.rate]");
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", "0.95"), Map.of("lines", List.of(
        Map.of("unit_price", "1.01", "qty", 1))))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> scalar(field("total", LogicalFieldType.INT32, false), "2147483648").evaluate(Map.of(), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> scalar(field("total", LogicalFieldType.INT64, false), "9223372036854775808").evaluate(Map.of(), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> scalar(field("total", LogicalFieldType.DECIMAL, false), "NULL").evaluate(Map.of(), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void usesPhysicalUnicodeBooleanDateAndMicrosecondTimestampContracts() {
    var text = new LogicalFieldDefinition("total", "total", "Total", LogicalFieldType.STRING, false, 1, null, null, null);
    assertThat(scalar(text, "LEFT(\"😀汉字\",1)").evaluate(Map.of(), Map.of()).parent()).containsEntry("total", "😀");
    assertThatThrownBy(() -> scalar(text, "\"😀汉\"").evaluate(Map.of(), Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThat(scalar(field("total", LogicalFieldType.BOOLEAN, false), "TRUE").evaluate(Map.of(), Map.of()).parent())
        .containsEntry("total", true);
    assertThat(scalar(field("total", LogicalFieldType.DATE, false), "TODAY()").evaluate(Map.of(), Map.of()).parent())
        .containsEntry("total", "2026-10-17");
    assertThat(scalar(field("total", LogicalFieldType.DATETIME, false), "NOW()").evaluate(Map.of(), Map.of()).parent())
        .containsEntry("total", "2026-10-17T10:00:00.123456Z");
    assertThatThrownBy(() -> scalar(field("total", LogicalFieldType.TIME, false), "\"10:00:00.1234567\"")
        .evaluate(Map.of(), Map.of())).isInstanceOf(DnaRuntimeException.class);
  }

  private RecordCalculationEvaluator expense(String expression) {
    return expense(expression, List.of());
  }

  @Test
  void validatesOnlyTheNewCalculatedAggregateAndRejectsOptionalPhysicalNullResultsWhenRequired() {
    var always = new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of());
    var bounded = new RecordValidationRule("bound", RecordValidationRule.Kind.RANGE,
        new RecordValidationField("parent", "total"), null, null, "0", "45", always);
    var evaluator = expense("ROUND([price]*[quantity]*[parent.rate],2)", List.of(bounded));
    var rows = Map.of("lines", List.of(Map.of("unit_price", "10", "qty", 3, "line_amount", "999")));
    assertThat(evaluator.evaluate(Map.of("exchange_rate", "1", "total_amount", "999"), rows).parent()).containsEntry("total_amount", "30.00");
    assertThatThrownBy(() -> evaluator.evaluate(Map.of("exchange_rate", "2", "total_amount", "0"), rows))
        .isInstanceOf(DnaRuntimeException.class).hasMessageContaining("published validation");
    var required = new RecordValidationRule("required", RecordValidationRule.Kind.REQUIRED,
        new RecordValidationField("parent", "total"), null, null, null, null, always);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field("total", LogicalFieldType.DECIMAL, true)), List.of());
    var nullable = new RecordCalculationEvaluator(compiler, model("parent", schema),
        new RecordCalculationDefinition("1", Map.of("total", "NULL"), Map.of(), null, List.of(required)), Map.of());
    assertThatThrownBy(() -> nullable.evaluate(Map.of("total", "123"), Map.of()))
        .isInstanceOf(DnaRuntimeException.class).hasMessageContaining("published validation");
  }

  private RecordCalculationEvaluator expense(String expression, List<RecordValidationRule> rules) {
    var parentSchema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT,
        List.of(decimal("rate", "exchange_rate"), decimal("total", "total_amount")), List.of());
    var detailSchema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
        field("parent_ref", LogicalFieldType.UUID, false),
        new LogicalFieldDefinition("order", "row_order", "Order", LogicalFieldType.INT32, false, null, null, null, null),
        decimal("price", "unit_price"), decimal("quantity", "qty"), decimal("amount", "line_amount")),
        List.of(), new OwnedModelRelation("parent", "parent_ref"));
    var definition = new RecordCalculationDefinition("1", Map.of("total", "IFERROR(SUM([lines.amount]),0)"), Map.of("lines",
        new RecordCalculationScope("detail", new RecordSchemaReference(1, codec.encode(detailSchema).checksum()), Map.of("amount", expression))), null, rules);
    return new RecordCalculationEvaluator(compiler, model("parent", parentSchema), definition, Map.of("lines", model("detail", detailSchema)));
  }

  private RecordCalculationEvaluator scalar(LogicalFieldDefinition field, String expression) {
    return new RecordCalculationEvaluator(compiler, model("parent", new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT,
        List.of(field), List.of())), new RecordCalculationDefinition("1", Map.of(field.fieldId(), expression), Map.of()), Map.of());
  }

  private static RuntimeRecordModel model(String id, PhysicalSchemaDefinition schema) {
    return new RuntimeRecordModel(id, "app", id + "-form", 1, schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("source", "", "public", id + "_table")));
  }

  private static LogicalFieldDefinition decimal(String id, String column) {
    return new LogicalFieldDefinition(id, column, id, LogicalFieldType.DECIMAL, false, null, 24, 2, null);
  }

  private static LogicalFieldDefinition field(String id, LogicalFieldType type, boolean nullable) {
    return new LogicalFieldDefinition(id, id, id, type, nullable, null, type == LogicalFieldType.DECIMAL ? 24 : null,
        type == LogicalFieldType.DECIMAL ? 2 : null, null);
  }
}
