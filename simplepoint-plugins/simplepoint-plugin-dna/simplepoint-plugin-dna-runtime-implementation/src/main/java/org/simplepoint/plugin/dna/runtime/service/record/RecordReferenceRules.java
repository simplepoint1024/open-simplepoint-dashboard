package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.service.schema.ReferenceFieldDependencies;

/** Compiles installed stable relation identities against the complete active source aggregate. */
final class RecordReferenceRules {
  private final Map<String, List<Rule>> scopes;
  private final Map<String, Map<String, LogicalFieldDefinition>> fields;

  RecordReferenceRules(
      RuntimeRecordModel parent,
      Map<String, RuntimeRecordModel> children,
      RecordCalculationDefinition definition,
      Map<String, Map<String, LogicalFieldDefinition>> fields) {
    this.fields = fields;
    Map<String, RuntimeRecordModel> models = new LinkedHashMap<>(children);
    models.put("parent", parent);
    Map<String, List<Rule>> compiled = new HashMap<>();
    var conditions = new RecordAggregateValidation(fields, List.of());
    int physicalCount =
        models.values().stream().mapToInt(model -> model.activeSchema().references().size()).sum();
    if (physicalCount != definition.referencesOrEmpty().size()) {
      throw incompatible();
    }
    var providers = new java.util.HashSet<RecordValidationField>();
    var references =
        definition.referencesOrEmpty().stream()
            .map(RecordReferenceRule::source)
            .collect(java.util.stream.Collectors.toSet());
    int mappingCount = 0;
    for (var rule : definition.referencesOrEmpty()) {
      var model = models.get(rule.source().scope());
      if (model == null) {
        throw incompatible();
      }
      var reference =
          model.activeSchema().references().stream()
              .filter(
                  candidate ->
                      candidate.relationId().equals(rule.relationId())
                          && candidate.fieldId().equals(rule.source().fieldId())
                          && candidate.targetModelId().equals(rule.targetModelId()))
              .findFirst()
              .orElseThrow(RecordReferenceRules::incompatible);
      var field = fields.get(rule.source().scope()).get(reference.fieldId());
      boolean required =
          model.requestedSchema().fields().stream()
              .filter(candidate -> candidate.fieldId().equals(reference.fieldId()))
              .findFirst()
              .map(candidate -> !candidate.nullable())
              .orElseThrow(RecordReferenceRules::incompatible);
      var formulas =
          rule.source().scope().equals("parent")
              ? definition.formulas()
              : definition.details().get(rule.source().scope()).formulas();
      if (field == null
          || field.type() != LogicalFieldType.UUID
          || rule.retired()
              && (!field.nullable()
                  || field.defaultValue() != null
                      && field.defaultValue().kind()
                          != org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.NULL)
          || formulas.containsKey(field.fieldId())) {
        throw incompatible();
      }
      mappingCount += rule.mappings().size();
      if (mappingCount > 128) {
        throw incompatible();
      }
      for (var mapping : rule.mappings()) {
        var destination = fields.get(rule.source().scope()).get(mapping.destinationFieldId());
        var target =
            reference.targetFields().stream()
                .filter(value -> value.fieldId().equals(mapping.targetFieldId()))
                .findFirst()
                .orElseThrow(RecordReferenceRules::incompatible);
        var identity =
            new RecordValidationField(rule.source().scope(), mapping.destinationFieldId());
        if (destination == null
            || !providers.add(identity)
            || references.contains(identity)
            || formulas.containsKey(mapping.destinationFieldId())
            || destination.defaultValue() != null
                && destination.defaultValue().kind()
                    != org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.NULL
            || !mapping.conversion().supports(target.type(), destination.type())) {
          throw incompatible();
        }
      }
      requireTargetScope(rule.eligibility());
      var when =
          rule.recheckWhen() == null
              ? (BiPredicate<Map<String, Object>, Map<String, Object>>) (root, row) -> false
              : conditions.conditionFor(rule.recheckWhen(), rule.source().scope());
      compiled
          .computeIfAbsent(rule.source().scope(), ignored -> new ArrayList<>())
          .add(new Rule(rule, model.physicalModelId(), field, when, required));
    }
    Map<String, List<Rule>> copy = new HashMap<>();
    compiled.forEach((scope, rules) -> copy.put(scope, List.copyOf(rules)));
    scopes = Map.copyOf(copy);
  }

  /** Checks are derived from stored and merged server values, including omitted retained fields. */
  List<RecordReferenceCheck> checks(
      String scope,
      String recordId,
      Map<String, Object> previous,
      Map<String, Object> prospective,
      Map<String, Object> parent) {
    return checks(scope, recordId, previous, prospective, parent, Map.of());
  }

  List<RecordReferenceCheck> checks(
      String scope,
      String recordId,
      Map<String, Object> previous,
      Map<String, Object> prospective,
      Map<String, Object> parent,
      Map<String, RecordReferenceMappingObservation> confirmations) {
    var rules = scopes.getOrDefault(scope, List.of());
    if (!rules.stream()
        .map(rule -> rule.definition().relationId())
        .collect(java.util.stream.Collectors.toSet())
        .containsAll(confirmations.keySet())) {
      throw incompatible();
    }
    if (rules.isEmpty()) {
      return List.of();
    }
    Map<String, Object> rootValues = stable("parent", parent);
    Map<String, Object> rowValues =
        scope.equals("parent") ? rootValues : stable(scope, prospective);
    List<RecordReferenceCheck> checks = new ArrayList<>();
    for (var rule : rules) {
      String target = identity(prospective.get(rule.field().code()));
      if (rule.definition().retired() && target != null || rule.required() && target == null) {
        throw conflict();
      }
      String prior = previous == null ? null : identity(previous.get(rule.field().code()));
      var confirmation = confirmations.get(rule.definition().relationId());
      boolean changed = !Objects.equals(prior, target);
      if (confirmation != null
              && (rule.definition().mappings().isEmpty()
                  || !Objects.equals(target, confirmation.targetRecordId()))
          || !rule.definition().mappings().isEmpty() && changed && confirmation == null) {
        throw conflict();
      }
      if (target != null
          && rule.modelId().equals(rule.definition().targetModelId())
          && target.equals(recordId)) {
        throw conflict();
      }
      boolean authorize =
          target != null
              && (previous == null
                  || changed
                  || confirmation != null
                  || rule.recheck().test(rootValues, rowValues));
      checks.add(
          new RecordReferenceCheck(
              rule.definition().relationId(),
              rule.modelId(),
              recordId,
              target,
              authorize,
              confirmation == null ? null : confirmation.targetVersion(),
              confirmation == null ? null : confirmation.revision()));
    }
    return List.copyOf(checks);
  }

  /**
   * Converts target stable field identities to the current schema without accepting storage names.
   */
  static RecordValueCondition targetCondition(
      RuntimeRecordModel target, RecordValidationCondition condition) {
    if (condition == null) {
      return null;
    }
    requireTargetScope(condition);
    String code =
        condition.field() == null
            ? null
            : target.requestedSchema().fields().stream()
                .filter(field -> field.fieldId().equals(condition.field().fieldId()))
                .findFirst()
                .orElseThrow(RecordReferenceRules::incompatible)
                .code();
    return new RecordValueCondition(
        condition.operator(),
        code,
        condition.values(),
        condition.children().stream().map(child -> targetCondition(target, child)).toList());
  }

  /** Rejects invalid target field/type/operator combinations before installing a declaration. */
  static RecordValueCondition validateTargetCondition(
      RuntimeRecordModel target, RecordValidationCondition condition) {
    if (condition == null) {
      return null;
    }
    var compiled = targetCondition(target, condition);
    try {
      RecordValueConditionCompiler.validateDefinition(target, compiled);
    } catch (DnaRuntimeException failure) {
      if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_QUERY_INVALID) {
        throw incompatible();
      }
      throw failure;
    }
    return compiled;
  }

  /**
   * New governance must declare its target dependencies in the immutable physical version first.
   */
  static void validateTargetDependencies(
      RuntimeRecordModel target,
      LogicalReferenceDefinition reference,
      RecordValidationCondition condition) {
    try {
      ReferenceFieldDependencies.requireCompatible(reference, target.activeSchema());
    } catch (DnaRuntimeException failure) {
      if (failure.errorCode() == DnaRuntimeErrorCode.SCHEMA_INVALID) {
        throw incompatible();
      }
      throw failure;
    }
    var declared =
        reference.targetFields().stream()
            .map(LogicalReferenceField::fieldId)
            .collect(java.util.stream.Collectors.toSet());
    requireDeclared(condition, declared);
  }

  /** Snapshot labels must be typed immutable dependencies, never arbitrary target columns. */
  static LogicalFieldDefinition snapshotLabel(
      RuntimeRecordModel target, LogicalReferenceDefinition reference, RecordReferenceRule rule) {
    return snapshotLabel(target, reference, rule.snapshotLabelFieldId());
  }

  static LogicalFieldDefinition snapshotLabel(
      RuntimeRecordModel target, LogicalReferenceDefinition reference, String fieldId) {
    if (fieldId == null) {
      return null;
    }
    if (reference.targetFields().stream().noneMatch(field -> field.fieldId().equals(fieldId))) {
      throw incompatible();
    }
    var field =
        target.activeSchema().fields().stream()
            .filter(value -> value.fieldId().equals(fieldId))
            .findFirst()
            .orElseThrow(RecordReferenceRules::incompatible);
    if ((field.type() != LogicalFieldType.STRING && field.type() != LogicalFieldType.TEXT)
        || reference.targetFields().stream()
            .noneMatch(
                dependency ->
                    dependency.fieldId().equals(field.fieldId())
                        && dependency.type() == field.type())) {
      throw incompatible();
    }
    return field;
  }

  private static void requireDeclared(
      RecordValidationCondition condition, java.util.Set<String> declared) {
    if (condition == null) {
      return;
    }
    if (condition.field() != null && !declared.contains(condition.field().fieldId())) {
      throw incompatible();
    }
    condition.children().forEach(child -> requireDeclared(child, declared));
  }

  /**
   * Shared by declaration admission and ordinary writes; neither can retarget a stable relation.
   */
  static void requireTargetIdentity(
      RecordReferenceRule rule, RuntimeRecordModel source, RuntimeRecordModel target) {
    if (!rule.targetModelId().equals(target.physicalModelId())
        || !rule.targetAppId().equals(target.appId())
        || !rule.targetFormId().equals(target.formId())
        || target.activeSchema().owner() != null
        || target.activeSchema().collection() != null
        || !Boolean.TRUE.equals(target.activeSchema().tenantRecordIdentity())
        || !target
            .target()
            .metadataTarget()
            .equals(source.target().referenceTargets().get(rule.targetModelId()))) {
      throw incompatible();
    }
  }

  private Map<String, Object> stable(String scope, Map<String, Object> physical) {
    Map<String, Object> values = new HashMap<>();
    fields
        .get(scope)
        .forEach(
            (id, field) ->
                values.put(id, RecordCalculationValues.typed(field, physical.get(field.code()))));
    return values;
  }

  private static void requireTargetScope(RecordValidationCondition condition) {
    if (condition == null) {
      return;
    }
    if (condition.field() != null && !condition.field().scope().equals("parent")) {
      throw incompatible();
    }
    condition.children().forEach(RecordReferenceRules::requireTargetScope);
  }

  static String identity(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String) && !(value instanceof UUID)) {
      throw conflict();
    }
    try {
      String raw = value.toString();
      String canonical = UUID.fromString(raw).toString();
      if (!canonical.equalsIgnoreCase(raw)) {
        throw conflict();
      }
      return canonical;
    } catch (IllegalArgumentException failure) {
      throw conflict();
    }
  }

  private static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "Installed reference policy does not match its source and target field identities");
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The operation conflicts with its reference policy");
  }

  private record Rule(
      RecordReferenceRule definition,
      String modelId,
      LogicalFieldDefinition field,
      BiPredicate<Map<String, Object>, Map<String, Object>> recheck,
      boolean required) {}
}
