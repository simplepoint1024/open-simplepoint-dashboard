package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingConfirmation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.service.support.LogicalValueInputs;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/**
 * Immutable authorized target inputs, loaded before source transaction admission. Each attempt
 * applies them to its own aggregate rows; it never mutates the request or shares a write buffer.
 */
final class RecordReferenceMappingWrite {
  private static final StandardLogicalValueConverter VALUES = new StandardLogicalValueConverter();
  private final RecordReferenceAdmission.Plan plan;
  private final String tenant;
  private final Map<Coordinate, List<Copy>> rows;

  private RecordReferenceMappingWrite(
      RecordReferenceAdmission.Plan plan, String tenant, Map<Coordinate, List<Copy>> rows) {
    this.plan = plan;
    this.tenant = tenant;
    Map<Coordinate, List<Copy>> immutable = new LinkedHashMap<>();
    rows.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
    this.rows = Map.copyOf(immutable);
  }

  /** Resolve exact selected fields in bounded batches, without borrowing a source connection. */
  static RecordReferenceMappingWrite load(
      RecordReferenceAdmission.Plan plan,
      MappedOwnedRecordCommand command,
      Map<String, RecordCalculationWriteSupport.Scope> sources,
      TargetReader reader) {
    var rootId = command.command().parent().physicalModelId();
    var root = sources.get(rootId);
    if (root == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    rootPolicy(plan, rootId);
    var declaredRoot = plan.sources().get(rootId);
    if (!declaredRoot.target().equals(root.model().target())
        || !declaredRoot.activeSchema().equals(root.model().activeSchema())) {
      throw RecordReferenceAdmission.incompatible();
    }
    String tenant = root.access().actor().tenantId();
    Map<String, List<RecordReferenceMappingConfirmation>> groups = new LinkedHashMap<>();
    for (var confirmation : command.confirmations()) {
      var binding = plan.bindings().get(confirmation.relationId());
      String sourceId =
          confirmation.detailModelId() == null ? rootId : confirmation.detailModelId();
      if (binding == null
          || !binding.sourceModelId().equals(sourceId)
          || binding.rule().mappings().isEmpty()) {
        throw RecordReferenceAdmission.incompatible();
      }
      var source = sources.get(sourceId);
      var declared = plan.sources().get(sourceId);
      if (source == null
          || declared == null
          || !root.access().actor().equals(source.access().actor())
          || !declared.target().equals(source.model().target())
          || !declared.activeSchema().equals(source.model().activeSchema())) {
        throw RecordReferenceAdmission.incompatible();
      }
      groups
          .computeIfAbsent(confirmation.relationId(), ignored -> new ArrayList<>())
          .add(confirmation);
    }
    Map<Coordinate, List<Copy>> rows = new LinkedHashMap<>();
    for (var group : groups.entrySet()) {
      var binding = plan.bindings().get(group.getKey());
      var rule = binding.rule();
      var source = sources.get(binding.sourceModelId());
      final Set<String> writable =
          source.access().writableFields().stream()
              .filter(code -> source.model().requestedField(code) != null)
              .map(source.model()::activeCode)
              .collect(java.util.stream.Collectors.toSet());
      Map<String, Set<String>> fieldsByTarget = new LinkedHashMap<>();
      for (var confirmation : group.getValue()) {
        var selected =
            RecordReferenceMappingFields.select(
                rule, Set.copyOf(confirmation.applyDefaultMappingIds()));
        var targetId = confirmation.observation().targetRecordId();
        if (targetId != null) {
          if (binding.target() == null
              || !root.access().actor().equals(binding.target().access().actor())) {
            throw conflict();
          }
          var mapping = plan.mapping(rule.relationId());
          if (!mapping.revision().equals(confirmation.observation().revision())) {
            throw conflict();
          }
          fieldsByTarget
              .computeIfAbsent(targetId, ignored -> new LinkedHashSet<>())
              .addAll(selected);
        }
      }
      // Group identical field sets: declining a default must not require its read permission merely
      // because a different row selected that default in the same request.
      Map<Set<String>, Set<String>> batches = new LinkedHashMap<>();
      fieldsByTarget.forEach(
          (id, fields) ->
              batches
                  .computeIfAbsent(Set.copyOf(fields), ignored -> new LinkedHashSet<>())
                  .add(id));
      Map<String, RecordReferenceMappingTargets.Captured> captured = new LinkedHashMap<>();
      for (var batch : batches.entrySet()) {
        var ids = new ArrayList<>(batch.getValue());
        for (int offset = 0; offset < ids.size(); offset += 100) {
          Set<String> selected =
              Set.copyOf(ids.subList(offset, Math.min(ids.size(), offset + 100)));
          var read = reader.read(binding.target(), selected, batch.getKey());
          if (!read.keySet().equals(selected)) {
            throw conflict();
          }
          captured.putAll(read);
        }
      }
      for (var confirmation : group.getValue()) {
        var observation = confirmation.observation();
        RecordReferenceCopyPlan.Prepared prepared;
        Map<String, List<RecordReferenceReadRequirement>> requirements;
        if (observation.targetRecordId() == null) {
          prepared =
              RecordReferenceMappingClear.bind(plan, rule.relationId(), tenant)
                  .prepare(
                      observation, writable, Set.copyOf(confirmation.applyDefaultMappingIds()));
          requirements = Map.of();
        } else {
          var target = captured.get(observation.targetRecordId());
          prepared =
              plan.mapping(rule.relationId())
                  .copies()
                  .prepare(
                      observation.targetRecordId(),
                      target.value(),
                      observation,
                      binding.target().access().readableFields(),
                      binding.target().access().maskedFields(),
                      writable,
                      Set.copyOf(confirmation.applyDefaultMappingIds()));
          requirements = target.requirements();
        }
        rows.computeIfAbsent(
                new Coordinate(binding.sourceModelId(), confirmation.rowIndex()),
                ignored -> new ArrayList<>())
            .add(new Copy(rule, confirmation.sourceRowId(), prepared, requirements));
      }
    }
    return new RecordReferenceMappingWrite(plan, tenant, rows);
  }

  private static void rootPolicy(RecordReferenceAdmission.Plan plan, String rootId) {
    var root = plan.sources().get(rootId);
    if (root == null
        || root.activeSchema().owner() != null
        || !root.appId().equals(plan.policy().policy().appId())
        || !root.formId().equals(plan.policy().policy().formId())) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  /** Apply only to final request coordinates after aggregate identity/version validation. */
  Applied apply(
      RecordCalculationWriteSupport.Binding binding,
      List<Input> inputs,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (binding == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    plan.verify(binding.installed());
    var matched = new LinkedHashMap<Coordinate, Input>();
    var identities = new HashSet<List<String>>();
    for (var input : inputs) {
      if (!identities.add(List.of(input.model().physicalModelId(), input.id()))) {
        throw RecordReferenceAdmission.incompatible();
      }
      var key = new Coordinate(input.model().physicalModelId(), input.requestIndex());
      if (rows.containsKey(key) && matched.putIfAbsent(key, input) != null) {
        throw RecordReferenceAdmission.incompatible();
      }
    }
    if (!matched.keySet().equals(rows.keySet())) {
      throw conflict();
    }
    Map<String, List<RecordView>> previous = new LinkedHashMap<>();
    matched.values().stream()
        .filter(input -> input.previous() != null)
        .forEach(
            input ->
                previous
                    .computeIfAbsent(input.model().physicalModelId(), ignored -> new ArrayList<>())
                    .add(input.previous()));
    var state =
        previous.isEmpty()
            ? new RecordReferenceCopyState(Map.of(), List.of())
            : RecordReferenceCopyState.current(
                binding.parent().physicalModelId(), tenant, previous, reader);
    Map<List<String>, Map<String, Object>> patches = new LinkedHashMap<>();
    Map<List<String>, RecordReferenceCopyEffects.Applied> effects = new LinkedHashMap<>();
    Map<List<String>, Map<String, RecordReferenceMappingObservation>> observations =
        new LinkedHashMap<>();
    for (var entry : matched.entrySet()) {
      var input = entry.getValue();
      var expectedModel = plan.sources().get(input.model().physicalModelId());
      if (expectedModel == null
          || !expectedModel.target().equals(input.model().target())
          || !expectedModel.activeSchema().equals(input.model().activeSchema())) {
        throw RecordReferenceAdmission.incompatible();
      }
      var key = List.of(input.model().physicalModelId(), input.id());
      var old = state.previous().get(key);
      var origins =
          old == null
              ? List.<org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin>of()
              : old.origins();
      Map<String, LogicalValue> changed = new LinkedHashMap<>();
      Map<String, Object> patch = new LinkedHashMap<>();
      Map<String, RecordReferenceMappingObservation> confirmed = new LinkedHashMap<>();
      for (var copy : rows.get(entry.getKey())) {
        if (input.requestIndex() != null
            && !Objects.equals(
                copy.previousId(), input.previous() == null ? null : input.previous().id())) {
          throw conflict();
        }
        String referenceCode = field(input.model(), copy.rule().source().fieldId()).code();
        Object selected = input.values().get(referenceCode);
        if (!Objects.equals(
            selected == null ? null : selected.toString(),
            copy.prepared().observed().targetRecordId())) {
          throw conflict();
        }
        confirmed.put(copy.rule().relationId(), copy.prepared().observed());
        var applied =
            RecordReferenceCopyEffects.apply(
                origins, copy.rule(), copy.prepared(), copy.requirements());
        for (var value : applied.values().entrySet()) {
          var field = field(input.model(), value.getKey());
          var mapping =
              copy.rule().mappings().stream()
                  .filter(item -> item.destinationFieldId().equals(value.getKey()))
                  .findFirst()
                  .orElseThrow();
          if (input.patch().containsKey(field.code())
              && (mapping.mode() == RecordReferenceMapping.Mode.SERVER_SNAPSHOT
                  || !VALUES
                      .normalize(field, input.patch().get(field.code()))
                      .equals(value.getValue()))) {
            throw conflict();
          }
          if (changed.putIfAbsent(value.getKey(), value.getValue()) != null) {
            throw RecordReferenceAdmission.incompatible();
          }
          patch.put(field.code(), LogicalValueInputs.fromCanonical(value.getValue()));
        }
        origins = applied.origins();
      }
      observations.put(key, Map.copyOf(confirmed));
      if (!changed.isEmpty()) {
        patches.put(key, Collections.unmodifiableMap(patch));
        effects.put(key, new RecordReferenceCopyEffects.Applied(changed, origins));
      }
    }
    return new Applied(Map.copyOf(patches), Map.copyOf(effects), Map.copyOf(observations));
  }

  private static org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition field(
      RuntimeRecordModel model, String id) {
    return model.activeSchema().fields().stream()
        .filter(value -> value.fieldId().equals(id))
        .findFirst()
        .orElseThrow(RecordReferenceAdmission::incompatible);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Confirmed mapping no longer matches the submitted aggregate or its authorization");
  }

  @FunctionalInterface
  interface TargetReader {
    Map<String, RecordReferenceMappingTargets.Captured> read(
        RecordReferenceAdmission.Target target, Set<String> ids, Set<String> fieldIds);
  }

  record Input(
      RuntimeRecordModel model,
      String id,
      Integer requestIndex,
      RecordView previous,
      Map<String, Object> patch,
      Map<String, Object> values) {}

  record Applied(
      Map<List<String>, Map<String, Object>> patches,
      Map<List<String>, RecordReferenceCopyEffects.Applied> effects,
      Map<List<String>, Map<String, RecordReferenceMappingObservation>> observations) {
    static Applied empty() {
      return new Applied(Map.of(), Map.of(), Map.of());
    }
  }

  private record Coordinate(String modelId, Integer rowIndex) {}

  private record Copy(
      RecordReferenceRule rule,
      String previousId,
      RecordReferenceCopyPlan.Prepared prepared,
      Map<String, List<RecordReferenceReadRequirement>> requirements) {}
}
