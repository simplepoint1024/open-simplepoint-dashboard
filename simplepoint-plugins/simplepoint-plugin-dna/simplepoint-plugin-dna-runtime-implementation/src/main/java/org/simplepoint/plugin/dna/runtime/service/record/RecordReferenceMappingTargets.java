package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Exact-entrance target reads; detached proof checks never hold the target read transaction. */
final class RecordReferenceMappingTargets {
  private final RuntimeRecordAccessPolicy policies;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationWriteSupport calculations;
  private final RecordCalculationAccess access;
  private final RecordReferenceCopyAuthorization authorization;
  private final PhysicalSchemaCodec schemas;

  RecordReferenceMappingTargets(
      RuntimeRecordAccessPolicy policies,
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationWriteSupport calculations,
      RecordCalculationAccess access,
      RecordReferenceCopyAuthorization authorization,
      PhysicalSchemaCodec schemas) {
    this.policies = policies;
    this.compiler = compiler;
    this.backend = backend;
    this.calculations = calculations;
    this.access = access;
    this.authorization = authorization;
    this.schemas = schemas;
  }

  Map<String, Captured> read(
      RecordReferenceAdmission.Target target,
      Set<String> ids,
      Set<String> fields,
      RecordExecutionDeadline deadline) {
    if (ids.isEmpty()
        || ids.size() > 100
        || fields.size() > 128
        || target.access().resourceGeneration() < 1) {
      throw conflict();
    }
    var prepared =
        prepare(
            target,
            calculations.bind(
                target.access().actor(), target.model(), target.snapshot().calculation()),
            policies,
            access.resolveReference(
                target.model(), target.access(), target.snapshot().calculation()),
            fields,
            compiler,
            schemas);
    var captured =
        backend.read(
            target.model(),
            prepared.children(),
            target.access(),
            deadline.remainingMillis(),
            session -> {
              if (session.revision() != target.snapshot().revision()
                  || !Objects.equals(session.calculation(), target.snapshot().calculation())) {
                throw conflict();
              }
              return prepared.capture(ids, session.query());
            });
    Set<RecordReferenceReadRequirement> required = new LinkedHashSet<>();
    captured.values().forEach(value -> value.requirements().values().forEach(required::addAll));
    if (required.size() > 16384) {
      throw conflict();
    }
    if (required.isEmpty()) {
      return captured;
    }
    var allowed = authorization.authorizeRequirements(target.access().actor(), required, deadline);
    if (!allowed.containsAll(required)) {
      throw conflict();
    }
    return captured;
  }

  static RecordReferenceTargetRead prepare(
      RecordReferenceAdmission.Target target,
      RecordCalculationWriteSupport.Binding binding,
      RuntimeRecordAccessPolicy policies,
      RecordCalculationAccess.Rules rules,
      Set<String> fields,
      RecordSqlCompiler compiler,
      PhysicalSchemaCodec schemas) {
    if (fields.size() > 128 || target.access().resourceGeneration() < 1) {
      throw conflict();
    }
    var selected = inputs(target.model(), binding, fields);
    Map<String, RecordReferenceTargetRead.Scope> scopes = new LinkedHashMap<>();
    scopes.put(
        "parent",
        new RecordReferenceTargetRead.Scope(
            target.model(), rules.apply(target.model(), target.access()), null));
    if (binding != null) {
      binding
          .details()
          .forEach(
              (name, model) -> {
                if (selected.containsKey(name)) {
                  scopes.put(
                      name,
                      new RecordReferenceTargetRead.Scope(
                          model,
                          rules.apply(
                              model,
                              policies.owned(target.access().actor(), model, target.access())),
                          OwnedRecordBinding.parentCode(target.model(), model)));
                }
              });
    }
    return new RecordReferenceTargetRead(
        target, binding, selected, scopes, fields, compiler, schemas);
  }

  private static Map<String, Set<String>> inputs(
      RuntimeRecordModel parent,
      RecordCalculationWriteSupport.Binding binding,
      Set<String> fields) {
    Set<String> inputs = new LinkedHashSet<>();
    fields.forEach(field -> inputs.add("parent." + field));
    var pending = new ArrayDeque<>(inputs);
    while (!pending.isEmpty()) {
      String input = pending.removeFirst();
      if (binding != null) {
        for (var dependency : binding.dependencies().getOrDefault(input, Set.of())) {
          if (inputs.add(dependency)) {
            pending.add(dependency);
          }
        }
      }
      if (inputs.size() > 2048) {
        throw conflict();
      }
    }
    Map<String, Set<String>> result = new LinkedHashMap<>();
    result.put("parent", new LinkedHashSet<>());
    for (String input : inputs) {
      int separator = input.indexOf('.');
      if (separator < 1) {
        throw conflict();
      }
      String scope = input.substring(0, separator);
      String field = input.substring(separator + 1);
      var model =
          scope.equals("parent") ? parent : binding == null ? null : binding.details().get(scope);
      if (model == null
          || model.requestedSchema().fields().stream()
              .noneMatch(value -> value.fieldId().equals(field))) {
        throw conflict();
      }
      result.computeIfAbsent(scope, ignored -> new LinkedHashSet<>()).add(field);
    }
    return result;
  }

  record Captured(
      RecordView value, Map<String, List<RecordReferenceReadRequirement>> requirements) {
    Captured {
      Map<String, List<RecordReferenceReadRequirement>> copy = new LinkedHashMap<>();
      requirements.forEach((field, required) -> copy.put(field, List.copyOf(required)));
      requirements = Map.copyOf(copy);
    }
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Reference mapping input, policy or original permission dependencies changed");
  }
}
