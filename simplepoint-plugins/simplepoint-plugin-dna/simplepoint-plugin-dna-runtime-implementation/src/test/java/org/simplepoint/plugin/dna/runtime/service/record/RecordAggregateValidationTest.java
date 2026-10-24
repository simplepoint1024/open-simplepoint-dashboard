package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class RecordAggregateValidationTest {
  private static final RecordValidationCondition ALWAYS = constant(true);
  private static final Map<String, Map<String, LogicalFieldDefinition>> FIELDS = Map.of(
      "parent", fields(field("state", LogicalFieldType.STRING), field("note", LogicalFieldType.STRING),
          field("total", LogicalFieldType.DECIMAL), field("json", LogicalFieldType.JSON), field("start", LogicalFieldType.DATE),
          field("end", LogicalFieldType.DATE), field("instant", LogicalFieldType.DATETIME)),
      "lines", fields(field("amount", LogicalFieldType.DECIMAL), field("note", LogicalFieldType.STRING)),
      "other", fields(field("note", LogicalFieldType.STRING)));

  @Test
  void evaluatesConditionalRequirementsWithExactDecimalsAndUnicodeMissingValues() {
    var submitted = leaf(RecordValueConditionOperator.EQUALS, "parent", "state", LogicalValueKind.STRING, "SUBMITTED");
    var expensive = leaf(RecordValueConditionOperator.GREATER_THAN_OR_EQUAL, "parent", "total", LogicalValueKind.DECIMAL, "9007199254740993.01");
    var validation = validator(required("parent", "note", group(RecordValueConditionOperator.AND, submitted, expensive)));
    Map<String, Object> values = new HashMap<>(Map.of("state", "DRAFT", "total", new BigDecimal("9007199254740993.01"), "note", "\u00a0\ufeff\n"));
    assertThatCode(() -> validation.validate(values, Map.of())).doesNotThrowAnyException();
    values.put("state", "SUBMITTED");
    assertThatThrownBy(() -> validation.validate(values, Map.of())).isInstanceOf(DnaRuntimeException.class)
        .hasMessageNotContaining("9007199254740993");
    values.put("total", new BigDecimal("9007199254740993.00"));
    assertThatCode(() -> validation.validate(values, Map.of())).doesNotThrowAnyException();
    values.put("total", new BigDecimal("9007199254740993.01"));
    values.put("note", "中文・日本語😀");
    assertThatCode(() -> validation.validate(values, Map.of())).doesNotThrowAnyException();
  }

  @Test
  void preservesNullNegationAndCanonicalJsonSemantics() {
    var validation = validator(required("parent", "note", leaf(RecordValueConditionOperator.NOT_EQUALS,
        "parent", "total", LogicalValueKind.DECIMAL, "1")));
    Map<String, Object> absent = new HashMap<>();
    absent.put("total", null);
    absent.put("note", null);
    assertThatThrownBy(() -> validation.validate(absent, Map.of())).isInstanceOf(DnaRuntimeException.class);
    var jsonRequired = validator(required("parent", "json", ALWAYS));
    for (String empty : List.of("null", "[]", "{}", "\"　\"")) {
      assertThatThrownBy(() -> jsonRequired.validate(Map.of("json", empty), Map.of())).isInstanceOf(DnaRuntimeException.class);
    }
    var equality = validator(required("parent", "note", leaf(RecordValueConditionOperator.EQUALS, "parent", "json",
        LogicalValueKind.JSON, "{\"b\":2.00,\"a\":1}")));
    assertThatThrownBy(() -> equality.validate(Map.of("json", "{\"a\":1,\"b\":2}"), Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatCode(() -> jsonRequired.validate(Map.of("json", "[null]"), Map.of())).doesNotThrowAnyException();
  }

  @Test
  void comparesThresholdsWithoutRoundingToPhysicalCapacity() {
    var decimal = validator(required("parent", "note", leaf(RecordValueConditionOperator.GREATER_THAN,
        "parent", "total", LogicalValueKind.DECIMAL, "0.005")));
    assertThatThrownBy(() -> decimal.validate(Map.of("total", new BigDecimal("0.01")), Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatCode(() -> decimal.validate(Map.of("total", new BigDecimal("0.00")), Map.of())).doesNotThrowAnyException();
    var temporal = validator(required("parent", "note", leaf(RecordValueConditionOperator.LESS_THAN,
        "parent", "instant", LogicalValueKind.DATETIME, "2026-10-18T00:00:00.000000001Z")));
    assertThatThrownBy(() -> temporal.validate(Map.of("instant", LocalDateTime.parse("2026-10-18T00:00:00")), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
    var longText = validator(required("parent", "note", leaf(RecordValueConditionOperator.NOT_EQUALS,
        "parent", "state", LogicalValueKind.STRING, "x".repeat(200))));
    assertThatThrownBy(() -> longText.validate(Map.of("state", "DRAFT"), Map.of())).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void appliesRowRequirementsOnlyInTheirOwnScopeAndParentState() {
    var when = group(RecordValueConditionOperator.AND,
        leaf(RecordValueConditionOperator.EQUALS, "parent", "state", LogicalValueKind.STRING, "SUBMITTED"),
        leaf(RecordValueConditionOperator.GREATER_THAN, "lines", "amount", LogicalValueKind.DECIMAL, "10"));
    var validation = validator(required("lines", "note", when));
    var good = Map.<String, Object>of("amount", new BigDecimal("11"), "note", "有凭证");
    var optional = Map.<String, Object>of("amount", BigDecimal.ONE);
    assertThatCode(() -> validation.validate(Map.of("state", "SUBMITTED"), Map.of("lines", List.of(good, optional))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validation.validate(Map.of("state", "SUBMITTED"), Map.of("lines", List.of(good, Map.of("amount", new BigDecimal("12"))))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatCode(() -> validation.validate(Map.of("state", "DRAFT"), Map.of("lines", List.of(Map.of("amount", new BigDecimal("12"))))))
        .doesNotThrowAnyException();
  }

  @Test
  void checksExactAggregateSumRangesCountsAndTemporalOrder() {
    var sum = new RecordValidationRule("sum", RecordValidationRule.Kind.DETAIL_SUM, ref("parent", "total"), ref("lines", "amount"), null, null, null, ALWAYS);
    var range = new RecordValidationRule("range", RecordValidationRule.Kind.RANGE, ref("lines", "amount"), null, null, "0", "100", ALWAYS);
    var count = new RecordValidationRule("count", RecordValidationRule.Kind.DETAIL_COUNT, null, null, "lines", "1", "40", ALWAYS);
    var after = new RecordValidationRule("after", RecordValidationRule.Kind.AFTER, ref("parent", "end"), ref("parent", "start"), null, null, null, ALWAYS);
    var validation = validator(sum, range, count, after);
    Map<String, Object> parent = new HashMap<>(Map.of("total", new BigDecimal("30.03"), "start", LocalDate.parse("2026-10-18"), "end", LocalDate.parse("2026-10-19")));
    var details = Map.of("lines", List.<Map<String, Object>>of(Map.of("amount", new BigDecimal("10.01")), Map.of("amount", new BigDecimal("20.02"))));
    assertThatCode(() -> validation.validate(parent, details)).doesNotThrowAnyException();
    parent.put("total", new BigDecimal("30.04"));
    assertThatThrownBy(() -> validation.validate(parent, details)).isInstanceOf(DnaRuntimeException.class);
    parent.put("total", new BigDecimal("30.03"));
    parent.put("end", parent.get("start"));
    assertThatThrownBy(() -> validation.validate(parent, details)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validator(count).validate(Map.of(), Map.of("lines", List.of()))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validator(range).validate(Map.of(), Map.of("lines", List.of(Map.of("amount", new BigDecimal("100.01"))))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatCode(() -> validator(sum).validate(Map.of("total", BigDecimal.ZERO), Map.of("lines", List.of())))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsUnknownFieldsCrossDetailConditionsAndWrongTypesEvenInDeadBranches() {
    var foreign = group(RecordValueConditionOperator.AND, constant(false), leaf(RecordValueConditionOperator.IS_EMPTY, "other", "note", null, null));
    assertThatThrownBy(() -> validator(required("lines", "note", foreign))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validator(required("parent", "physical_total", ALWAYS))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validator(required("parent", "note", leaf(RecordValueConditionOperator.EQUALS,
        "parent", "total", LogicalValueKind.STRING, "1")))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validator(new RecordValidationRule("r", RecordValidationRule.Kind.RANGE, ref("parent", "note"), null, null, "1", null, ALWAYS)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validator(required("parent", "note", leaf(RecordValueConditionOperator.EQUALS,
        "parent", "total", LogicalValueKind.DECIMAL, "1E-2147483647")))).isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordAggregateValidation validator(RecordValidationRule... rules) {
    return new RecordAggregateValidation(FIELDS, List.of(rules));
  }

  private static RecordValidationRule required(String scope, String field, RecordValidationCondition when) {
    return new RecordValidationRule("required", RecordValidationRule.Kind.REQUIRED, ref(scope, field), null, null, null, null, when);
  }

  private static RecordValidationField ref(String scope, String field) {
    return new RecordValidationField(scope, field);
  }

  private static RecordValidationCondition leaf(RecordValueConditionOperator operator, String scope, String field, LogicalValueKind kind, String value) {
    return new RecordValidationCondition(operator, ref(scope, field), kind == null ? List.of() : List.of(new LogicalValue(kind, value)), List.of());
  }

  @Test
  void appliesIntersectingHistoricalTextRulesUsingUnicodeAndBoundedRe2() {
    var a = new RecordValidationRule("a", RecordValidationRule.Kind.FIELD, ref("parent", "note"), null, null, null, null, ALWAYS,
        new org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints(2, 3, "^中", List.of()));
    var b = new RecordValidationRule("b", RecordValidationRule.Kind.FIELD, ref("parent", "note"), null, null, null, null, ALWAYS,
        new org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints(null, 2, "😀$", List.of()));
    var check = validator(a, b);
    assertThatCode(() -> check.validate(Map.of("note", "中😀"), Map.of())).doesNotThrowAnyException();
    assertThatCode(() -> check.validate(Map.of("note", ""), Map.of())).doesNotThrowAnyException();
    assertThatCode(() -> check.validate(Map.of(), Map.of())).doesNotThrowAnyException();
    for (String rejected : List.of("中文", "中日😀", "日😀")) {
      assertThatThrownBy(() -> check.validate(Map.of("note", rejected), Map.of())).isInstanceOf(DnaRuntimeException.class)
          .hasMessageNotContaining(rejected);
    }
    var invalid = new RecordValidationRule("invalid", RecordValidationRule.Kind.FIELD, ref("parent", "note"), null, null, null, null,
        constant(false), new org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints(null, null, "(?=x)", List.of()));
    assertThatThrownBy(() -> validator(invalid)).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void appliesHistoricalChoicesAtTheirLifecycleBoundaryIncludingOwnedRows() {
    var submitted = leaf(RecordValueConditionOperator.EQUALS, "parent", "state", LogicalValueKind.STRING, "SUBMITTED");
    var rule = new RecordValidationRule("choices", RecordValidationRule.Kind.FIELD, ref("lines", "note"), null, null, null, null, submitted,
        new org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints(null, null, null,
            List.of(new LogicalValue(LogicalValueKind.STRING, "approved"))));
    var check = validator(rule);
    var rows = Map.of("lines", List.<Map<String, Object>>of(Map.of("note", "old-value")));
    assertThatCode(() -> check.validate(Map.of("state", "DRAFT"), rows)).doesNotThrowAnyException();
    assertThatThrownBy(() -> check.validate(Map.of("state", "SUBMITTED"), rows)).isInstanceOf(DnaRuntimeException.class);
    assertThatCode(() -> check.validate(Map.of("state", "SUBMITTED"), Map.of("lines", List.of(Map.of())))).doesNotThrowAnyException();
    assertThatThrownBy(() -> check.validate(Map.of("state", "SUBMITTED"), Map.of("lines", List.of(Map.of("note", "")))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordValidationCondition group(RecordValueConditionOperator operator, RecordValidationCondition... children) {
    return new RecordValidationCondition(operator, null, List.of(), List.of(children));
  }

  private static RecordValidationCondition constant(boolean value) {
    return new RecordValidationCondition(value ? RecordValueConditionOperator.TRUE : RecordValueConditionOperator.FALSE, null, List.of(), List.of());
  }

  private static LogicalFieldDefinition field(String id, LogicalFieldType type) {
    return new LogicalFieldDefinition(id, "physical_" + id, id, type, true, type == LogicalFieldType.STRING ? 32 : null,
        type == LogicalFieldType.DECIMAL ? 24 : null, type == LogicalFieldType.DECIMAL ? 2 : null, null);
  }

  private static Map<String, LogicalFieldDefinition> fields(LogicalFieldDefinition... fields) {
    return java.util.Arrays.stream(fields).collect(java.util.stream.Collectors.toMap(LogicalFieldDefinition::fieldId, java.util.function.Function.identity()));
  }
}
