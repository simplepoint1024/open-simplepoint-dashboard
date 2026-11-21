package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.simplepoint.calculation.FormulaAggregatePlan;
import org.simplepoint.calculation.FormulaEngine;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.calculation.StandardFunctions;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Compiles stable calculation identities against tenant-resolved model metadata, without I/O. */
final class RecordCalculationCompiler {
  private final PhysicalSchemaCodec schemas;
  private final FormulaEngine engine;

  RecordCalculationCompiler(final PhysicalSchemaCodec schemas, final Clock clock) {
    this.schemas = Objects.requireNonNull(schemas, "schemas");
    this.engine =
        new FormulaEngine(
            StandardFunctions.all(),
            Clock.tick(clock.withZone(ZoneOffset.UTC), Duration.ofNanos(1000)));
  }

  /**
   * Callers must resolve every model under the same actor before entering this boundary. A typed
   * result adapter is mandatory so downstream formulas cannot bypass physical field validation.
   * Compilation does not install or activate the definition and does not authorize record reads.
   */
  Compiled compile(
      final RuntimeRecordModel parent,
      final RecordCalculationDefinition definition,
      final Map<String, RuntimeRecordModel> children,
      final BiFunction<LogicalFieldDefinition, Object, Object> results) {
    Objects.requireNonNull(results, "results");
    if (parent.activeSchema().owner() != null
        || parent.requestedSchema().owner() != null
        || parent.activeSchema().collection() != null
        || parent.requestedSchema().collection() != null
        || !children.keySet().equals(definition.details().keySet())) {
      throw invalid("Calculation aggregate scope does not match the resolved models");
    }
    Map<String, Map<String, LogicalFieldDefinition>> fields = new LinkedHashMap<>();
    Map<String, FormulaAggregatePlan.Scope> details = new LinkedHashMap<>();
    try {
      var root = scope("parent", parent, definition.formulas(), fields);
      for (var entry : definition.details().entrySet()) {
        var declared = entry.getValue();
        var child = children.get(entry.getKey());
        if (!declared.physicalModelId().equals(child.physicalModelId())
            || declared.schemaReference().version() != child.requestedVersion()
            || !declared
                .schemaReference()
                .checksum()
                .equals(schemas.encode(child.requestedSchema()).checksum())) {
          throw invalid("Calculation detail schema identity does not match");
        }
        requireOwnedTarget(parent, child);
        details.put(entry.getKey(), scope(entry.getKey(), child, declared.formulas(), fields));
      }
      var constraints = new RecordCalculationConstraints(fields, definition);
      var validation = new RecordAggregateValidation(fields, definition.validationsOrEmpty());
      var transitions =
          new RecordStateTransitionValidation(
              fields.get("parent"), definition.formulas().keySet(), definition.stateTransitions());
      var writes = new RecordFieldWriteValidation(fields, definition);
      var references = new RecordReferenceRules(parent, children, definition, fields);
      return new Compiled(
          new FormulaAggregatePlan(
              engine,
              root,
              details,
              (scope, field, value) ->
                  constraints.validate(
                      scope, field, results.apply(fields.get(scope).get(field), value))),
          Map.copyOf(fields),
          definition,
          constraints,
          validation,
          transitions,
          writes,
          references);
    } catch (FormulaException failure) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Invalid calculation graph: " + failure.code(),
          failure);
    }
  }

  private static FormulaAggregatePlan.Scope scope(
      final String name,
      final RuntimeRecordModel model,
      final Map<String, String> formulas,
      final Map<String, Map<String, LogicalFieldDefinition>> scopes) {
    Map<String, LogicalFieldDefinition> fields = new LinkedHashMap<>();
    var owner = model.requestedSchema().owner();
    for (var field : model.requestedSchema().fields()) {
      if (owner != null
          && (field.fieldId().equals(owner.parentFieldId()) || "row_order".equals(field.code()))) {
        continue;
      }
      fields.put(field.fieldId(), model.activeField(field.code()));
    }
    if (!fields.keySet().containsAll(formulas.keySet())) {
      throw invalid("Calculation target is absent or server managed");
    }
    for (String field : formulas.keySet()) {
      if (fields.get(field).defaultValue() != null) {
        throw invalid("Calculated fields cannot have a storage default");
      }
    }
    var inputs = new LinkedHashSet<>(fields.keySet());
    inputs.removeAll(formulas.keySet());
    scopes.put(name, Map.copyOf(fields));
    return new FormulaAggregatePlan.Scope(inputs, formulas);
  }

  private static void requireOwnedTarget(
      final RuntimeRecordModel parent, final RuntimeRecordModel child) {
    var owner = child.activeSchema().owner();
    var target = parent.target().metadataTarget();
    var detailTarget = child.target().metadataTarget();
    // Form ids differ for owned models; ownership is defined by their immutable parent relation.
    if (owner == null
        || !owner.equals(child.requestedSchema().owner())
        || !owner.parentModelId().equals(parent.physicalModelId())
        || child.physicalModelId().equals(parent.physicalModelId())
        || !child.appId().equals(parent.appId())
        || !target.dataSourceId().equals(detailTarget.dataSourceId())
        || !Objects.equals(target.catalogName(), detailTarget.catalogName())
        || !Objects.equals(target.schemaName(), detailTarget.schemaName())
        || target.tableName().equals(detailTarget.tableName())) {
      throw invalid("Calculation detail does not belong to this physical aggregate");
    }
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }

  /** Trusted physical fields and dependency graph for transaction execution and read policy. */
  record Compiled(
      FormulaAggregatePlan plan,
      Map<String, Map<String, LogicalFieldDefinition>> fields,
      RecordCalculationDefinition definition,
      RecordCalculationConstraints constraints,
      RecordAggregateValidation validation,
      RecordStateTransitionValidation transitions,
      RecordFieldWriteValidation writes,
      RecordReferenceRules references) {}
}
