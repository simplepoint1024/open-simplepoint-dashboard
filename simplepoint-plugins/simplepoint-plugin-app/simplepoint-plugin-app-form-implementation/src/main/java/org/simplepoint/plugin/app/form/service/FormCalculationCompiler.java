package org.simplepoint.plugin.app.form.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.calculation.FormulaAggregatePlan;
import org.simplepoint.calculation.FormulaEngine;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.calculation.StandardFunctions;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculationDiagnostic;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

/**
 * Form-domain adapter for the independent engine; it performs no database or authorization reads.
 */
public final class FormCalculationCompiler {
  private final FormulaEngine engine;

  /** Uses one UTC microsecond clock, matching the platform's persisted timestamp precision. */
  public FormCalculationCompiler(Clock clock) {
    engine =
        new FormulaEngine(
            StandardFunctions.all(),
            Clock.tick(clock.withZone(ZoneOffset.UTC), Duration.ofNanos(1000)));
  }

  /** Describes the same immutable registry used to compile and evaluate form expressions. */
  List<org.simplepoint.calculation.FormulaFunction> functions() {
    return engine.functions();
  }

  /** Checks the entire dependency graph, including unused branches and currently empty details. */
  public Compiled compile(ApplicationFormDocument document) {
    try {
      Map<String, List<FormFieldDefinition>> declarations = new LinkedHashMap<>();
      declarations.put("parent", document.fields());
      for (var detail : document.details()) {
        if (declarations.putIfAbsent(detail.detailId(), detail.fields()) != null) {
          throw new FormulaException("DUPLICATE_FIELD");
        }
      }
      Map<String, FormulaAggregatePlan.Scope> scopes = new LinkedHashMap<>();
      Map<String, FormFieldDefinition> fields = new LinkedHashMap<>();
      declarations.forEach(
          (scope, definitions) -> {
            Set<String> inputs = new LinkedHashSet<>();
            Map<String, String> formulas = new LinkedHashMap<>();
            for (var field : definitions) {
              if (fields.putIfAbsent(scope + "." + field.fieldId(), field) != null) {
                throw new FormulaException("DUPLICATE_FIELD");
              }
              if (field.calculation() != null) {
                if (field.storageMode() != FormStorageMode.COMPUTED
                    || field.defaultValue() != null
                    || field.writePolicy() != null) {
                  throw new FormulaException("FIELD_CONFIGURATION");
                }
                formulas.put(field.fieldId(), field.calculation().expression());
              } else if (field.persistent()) {
                inputs.add(field.fieldId());
              }
            }
            scopes.put(scope, new FormulaAggregatePlan.Scope(inputs, formulas));
          });
      var parent = scopes.remove("parent");
      var plan =
          new FormulaAggregatePlan(
              engine,
              parent,
              scopes,
              (scope, field, value) ->
                  FormCalculationValues.typed(fields.get(scope + "." + field), value));
      return new Compiled(plan, Map.copyOf(fields), parent, Map.copyOf(scopes));
    } catch (FormulaException error) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.DOCUMENT_INVALID,
          "Invalid form calculation: " + error.code(),
          error,
          diagnostic(error));
    }
  }

  private static FormCalculationDiagnostic diagnostic(FormulaException error) {
    return new FormCalculationDiagnostic(
        error.code(), error.line(), error.column(), error.scope(), error.field(), error.row());
  }

  /**
   * Immutable compiled graph. Callers supply a complete, authorized, version-consistent aggregate.
   */
  public record Compiled(
      FormulaAggregatePlan plan,
      Map<String, FormFieldDefinition> fields,
      FormulaAggregatePlan.Scope parent,
      Map<String, FormulaAggregatePlan.Scope> details) {

    /** Retains immutable metadata even when constructed by an embedding host. */
    public Compiled {
      fields = Map.copyOf(fields);
      details = Map.copyOf(details);
    }

    /** Dependency identities are qualified by parent/detail and include transitive sources. */
    public Map<String, Set<String>> dependencies() {
      return plan.dependencies();
    }

    /** Returns computed fields only; caller-owned maps and detail ordering remain untouched. */
    public FormulaAggregatePlan.Result evaluate(
        Map<String, ?> parentValues,
        Map<String, ? extends List<? extends Map<String, ?>>> detailValues) {
      try {
        if (!detailValues.keySet().equals(details.keySet())) {
          throw new FormulaException("UNKNOWN_COLLECTION");
        }
        if (detailValues.values().stream().mapToLong(List::size).sum() > 200) {
          throw new FormulaException("VALUE_SIZE");
        }
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        details.forEach(
            (scope, declaration) ->
                rows.put(
                    scope,
                    java.util.stream.IntStream.range(0, detailValues.get(scope).size())
                        .mapToObj(
                            index -> {
                              try {
                                return inputs(
                                    scope, declaration, detailValues.get(scope).get(index));
                              } catch (FormulaException failure) {
                                throw new FormulaException(
                                        failure.code(), failure.line(), failure.column())
                                    .atField(scope, failure.field(), index);
                              }
                            })
                        .toList()));
        var result = plan.evaluate(inputs("parent", parent, parentValues), rows);
        Map<String, List<Map<String, Object>>> outputs = new LinkedHashMap<>();
        result
            .details()
            .forEach(
                (scope, values) ->
                    outputs.put(scope, values.stream().map(row -> wire(scope, row)).toList()));
        return new FormulaAggregatePlan.Result(wire("parent", result.parent()), outputs);
      } catch (FormulaException error) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.RECORD_INVALID,
            "Form calculation failed: " + error.code(),
            error,
            diagnostic(error));
      }
    }

    private Map<String, Object> inputs(
        String scope, FormulaAggregatePlan.Scope declaration, Map<String, ?> source) {
      for (String id : source.keySet()) {
        if (declaration.formulas().containsKey(id)) {
          throw new FormulaException("COMPUTED_WRITE");
        }
        if (!declaration.inputs().contains(id)) {
          throw new FormulaException("UNKNOWN_FIELD");
        }
      }
      Map<String, Object> values = new LinkedHashMap<>();
      declaration
          .inputs()
          .forEach(
              id -> {
                try {
                  values.put(
                      id,
                      FormCalculationValues.typed(fields.get(scope + "." + id), source.get(id)));
                } catch (FormulaException failure) {
                  throw failure.atField(scope, id, null);
                }
              });
      return Collections.unmodifiableMap(values);
    }

    private Map<String, Object> wire(String scope, Map<String, Object> source) {
      Map<String, Object> values = new LinkedHashMap<>();
      source.forEach(
          (id, value) ->
              values.put(id, FormCalculationValues.wire(fields.get(scope + "." + id), value)));
      return values;
    }
  }

  /**
   * Legacy non-persistent COMPUTED placeholders have no formula and keep their original contract.
   */
  public static boolean hasCalculations(ApplicationFormDocument document) {
    return document.fields().stream().anyMatch(field -> field.calculation() != null)
        || document.details().stream()
            .flatMap(detail -> detail.fields().stream())
            .anyMatch(field -> field.calculation() != null);
  }
}
