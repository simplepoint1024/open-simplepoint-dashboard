package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.calculation.FormulaAggregatePlan;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;

/**
 * Computes from complete, trusted, transaction-local physical input snapshots. It does not read
 * storage, authorize requests or accept client formulas. Ordinary request validation must reject
 * writes to computed columns before merging input with persisted rows.
 */
final class RecordCalculationEvaluator {
  private final RecordCalculationCompiler.Compiled compiled;

  RecordCalculationEvaluator(final RecordCalculationCompiler compiler, final RuntimeRecordModel parent,
      final RecordCalculationDefinition definition, final Map<String, RuntimeRecordModel> details) {
    compiled = compiler.compile(parent, definition, details, RecordCalculationValues::typed);
  }

  /** Returns computed physical columns only; inputs, row order and system audit values remain untouched. */
  FormulaAggregatePlan.Result evaluate(final Map<String, ?> parent,
      final Map<String, ? extends List<? extends Map<String, ?>>> details) {
    if (parent == null || details == null || !details.keySet().equals(compiled.definition().details().keySet())
        || details.values().stream().anyMatch(rows -> rows == null || rows.size() > 40)
        || details.values().stream().mapToLong(List::size).sum() > 200) {
      throw invalid("Calculation input does not contain the bounded complete aggregate", null);
    }
    try {
      compiled.transitions().validateTarget(parent);
      Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
      details.forEach((scope, values) -> rows.put(scope, values.stream().map(row -> inputs(scope, row)).toList()));
      var parentInputs = inputs("parent", parent);
      var evaluated = compiled.plan().evaluate(parentInputs, rows);
      Map<String, List<Map<String, Object>>> complete = new LinkedHashMap<>();
      rows.forEach((scope, values) -> complete.put(scope, java.util.stream.IntStream.range(0, values.size())
          .mapToObj(index -> merged(values.get(index), evaluated.details().get(scope).get(index))).toList()));
      compiled.validation().validate(merged(parentInputs, evaluated.parent()), complete);
      Map<String, List<Map<String, Object>>> outputs = new LinkedHashMap<>();
      evaluated.details().forEach((scope, values) -> outputs.put(scope, values.stream().map(row -> wire(scope, row)).toList()));
      return new FormulaAggregatePlan.Result(wire("parent", evaluated.parent()), outputs);
    } catch (FormulaException failure) {
      throw invalid("Record calculation failed: " + failure.code(), failure);
    }
  }

  Map<String, java.util.Set<String>> dependencies() {
    return compiled.plan().dependencies();
  }

  void validateTransition(final Map<String, ?> previous, final Map<String, ?> prospective) {
    compiled.transitions().validateTransition(previous, prospective);
  }

  void validateWrite(final String scope, final Map<String, ?> patch, final Map<String, ?> previousParent) {
    compiled.writes().validate(scope, patch, previousParent);
  }

  private static Map<String, Object> merged(final Map<String, Object> inputs, final Map<String, Object> outputs) {
    Map<String, Object> values = new LinkedHashMap<>(inputs);
    values.putAll(outputs);
    return values;
  }

  private Map<String, Object> inputs(final String scope, final Map<String, ?> source) {
    if (source == null) {
      throw invalid("Calculation input row is missing", null);
    }
    var formulas = "parent".equals(scope) ? compiled.definition().formulas()
        : compiled.definition().details().get(scope).formulas();
    Map<String, Object> values = new LinkedHashMap<>();
    compiled.fields().get(scope).forEach((id, field) -> {
      if (!formulas.containsKey(id)) {
        if (!source.containsKey(field.code())) {
          throw invalid("Calculation input is missing a physical source field", null);
        }
        values.put(id, compiled.constraints().validate(scope, id, RecordCalculationValues.typed(field, source.get(field.code()))));
      }
    });
    return Collections.unmodifiableMap(values);
  }

  private Map<String, Object> wire(final String scope, final Map<String, Object> source) {
    Map<String, Object> values = new LinkedHashMap<>();
    source.forEach((id, value) -> {
      var field = compiled.fields().get(scope).get(id);
      values.put(field.code(), RecordCalculationValues.wire(field, value));
    });
    return values;
  }

  private static DnaRuntimeException invalid(final String message, final Throwable failure) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message, failure);
  }
}
