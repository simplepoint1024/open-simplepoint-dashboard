package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Pure calculation-origin assembly from complete prospective physical rows and trusted ledgers. */
final class RecordDerivedCopyRequirements {
  private RecordDerivedCopyRequirements() {}

  /**
   * Values use active physical field codes, like the calculation evaluator. Input ledgers must
   * already carry the prospective business versions. This does not authorize inputs or commit them.
   */
  static List<RecordReferenceCopySnapshot> capture(
      RecordCalculationWriteSupport.Binding binding,
      OwnedRecordView physicalValues,
      List<RecordReferenceCopySnapshot> inputOrigins) {
    String parentId = binding.parent().physicalModelId();
    Map<String, RuntimeRecordModel> scopes = new LinkedHashMap<>(binding.details());
    scopes.put("parent", binding.parent());
    Map<String, List<RecordView>> rows = new LinkedHashMap<>(physicalValues.details());
    if (rows.putIfAbsent(parentId, List.of(physicalValues.parent())) != null
        || rows.size() > 6
        || rows.values().stream().mapToInt(List::size).sum() > 201) {
      throw invalid();
    }
    rows.forEach(
        (model, values) -> {
          if (values.stream().map(RecordView::id).distinct().count() != values.size()) {
            throw invalid();
          }
        });
    Map<List<String>, RecordReferenceCopySnapshot> prior = new LinkedHashMap<>();
    for (var snapshot : inputOrigins) {
      String model = snapshot.detailModelId() == null ? parentId : snapshot.detailModelId();
      var identity = List.of(model, snapshot.sourceRecordId());
      if (prior.putIfAbsent(identity, snapshot) != null
          || rows.getOrDefault(model, List.of()).stream()
              .noneMatch(
                  row ->
                      row.id().equals(snapshot.sourceRecordId())
                          && row.version() == snapshot.sourceVersion())) {
        throw invalid();
      }
    }
    Map<List<String>, Map<String, RecordDerivedCopyOrigin>> derived = new LinkedHashMap<>();
    prior.forEach(
        (key, value) -> {
          var retained = new LinkedHashMap<String, RecordDerivedCopyOrigin>();
          value
              .derivedOrigins()
              .forEach(origin -> retained.put(origin.destinationFieldId(), origin));
          derived.put(key, retained);
        });
    String checksum = RecordCalculationStore.policyChecksum(binding.installed().policy());
    for (String output : binding.dependencies().keySet()) {
      var destination = field(scopes, output);
      var outputRows = rows.get(destination.model().physicalModelId());
      if (outputRows == null) {
        throw invalid();
      }
      var dependencies = closure(binding.dependencies(), output);
      for (var row : outputRows) {
        if (!row.values().containsKey(destination.code())) {
          throw invalid();
        }
        var local = new LinkedHashMap<List<String>, RecordAggregateReadRequirement>();
        var external = new LinkedHashMap<List<String>, RecordReferenceReadRequirement>();
        for (String dependency : dependencies) {
          var input = field(scopes, dependency);
          var inputs =
              input.model().physicalModelId().equals(destination.model().physicalModelId())
                  ? List.of(row)
                  : rows.get(input.model().physicalModelId());
          if (inputs == null) {
            throw invalid();
          }
          if (inputs.isEmpty()) {
            mergeLocal(
                local,
                new RecordAggregateReadRequirement(
                    input.model().physicalModelId(), null, List.of(input.id())));
          }
          for (var inputRow : inputs) {
            if (!inputRow.values().containsKey(input.code())
                || "***".equals(inputRow.values().get(input.code()))) {
              throw invalid();
            }
            mergeLocal(
                local,
                new RecordAggregateReadRequirement(
                    input.model().physicalModelId(), inputRow.id(), List.of(input.id())));
            // Recomputed intermediates use this evaluation's graph, never their older result's
            // proof.
            if (binding.dependencies().containsKey(dependency)) {
              continue;
            }
            var previous = prior.get(List.of(input.model().physicalModelId(), inputRow.id()));
            if (previous == null) {
              continue;
            }
            previous.origins().stream()
                .filter(origin -> origin.destinationFieldId().equals(input.id()))
                .forEach(
                    origin ->
                        origin
                            .requirements()
                            .forEach(requirement -> mergeExternal(external, requirement)));
            previous.derivedOrigins().stream()
                .filter(origin -> origin.destinationFieldId().equals(input.id()))
                .forEach(
                    origin -> {
                      origin
                          .requirements()
                          .forEach(requirement -> mergeExternal(external, requirement));
                      origin
                          .localRequirements()
                          .forEach(requirement -> mergeLocal(local, requirement));
                    });
          }
        }
        var key = List.of(destination.model().physicalModelId(), row.id());
        var outputs = derived.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        // An actual recalculation from ordinary inputs may clear its old external dependency.
        // Merely removing a formula never visits this branch and therefore preserves its proof.
        outputs.remove(destination.id());
        if (!external.isEmpty()) {
          outputs.put(
              destination.id(),
              new RecordDerivedCopyOrigin(
                  1,
                  destination.id(),
                  binding.installed().revision(),
                  checksum,
                  List.copyOf(local.values()),
                  List.copyOf(external.values())));
        }
      }
    }
    Map<String, Set<String>> recomputed = new LinkedHashMap<>();
    binding
        .dependencies()
        .keySet()
        .forEach(
            output -> {
              var field = field(scopes, output);
              recomputed
                  .computeIfAbsent(
                      field.model().physicalModelId(), ignored -> new LinkedHashSet<>())
                  .add(field.id());
            });
    var result = new ArrayList<RecordReferenceCopySnapshot>();
    rows.forEach(
        (model, values) ->
            values.forEach(
                row -> {
                  var key = List.of(model, row.id());
                  var old = prior.get(key);
                  var copies =
                      old == null
                          ? List
                              .<org.simplepoint.plugin.dna.runtime.api.record
                                      .RecordReferenceCopyOrigin>
                                  of()
                          : old.origins().stream()
                              .filter(
                                  origin ->
                                      !recomputed
                                          .getOrDefault(model, Set.of())
                                          .contains(origin.destinationFieldId()))
                              .toList();
                  var outputs = List.copyOf(derived.getOrDefault(key, Map.of()).values());
                  if (!copies.isEmpty() || !outputs.isEmpty()) {
                    result.add(
                        new RecordReferenceCopySnapshot(
                            model.equals(parentId) ? null : model,
                            row.id(),
                            row.version(),
                            copies,
                            outputs));
                  }
                }));
    return List.copyOf(result);
  }

  private static Set<String> closure(Map<String, Set<String>> graph, String output) {
    var pending = new ArrayDeque<>(graph.getOrDefault(output, Set.of()));
    Set<String> result = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      String input = pending.removeFirst();
      if (input.equals(output)) {
        throw invalid();
      }
      if (result.add(input)) {
        if (result.size() > 2048) {
          throw invalid();
        }
        pending.addAll(graph.getOrDefault(input, Set.of()));
      }
    }
    return result;
  }

  private static Input field(Map<String, RuntimeRecordModel> scopes, String path) {
    int separator = path.indexOf('.');
    var model = separator < 1 ? null : scopes.get(path.substring(0, separator));
    String id = separator < 1 ? "" : path.substring(separator + 1);
    var field =
        model == null
            ? null
            : model.requestedSchema().fields().stream()
                .filter(candidate -> candidate.fieldId().equals(id))
                .findFirst()
                .orElse(null);
    if (field == null) {
      throw invalid();
    }
    return new Input(model, id, model.activeCode(field.code()));
  }

  private static void mergeLocal(
      Map<List<String>, RecordAggregateReadRequirement> values,
      RecordAggregateReadRequirement value) {
    var previous = values.get(value.rowIdentity());
    if (previous != null) {
      var fields = new LinkedHashSet<>(previous.fieldIds());
      fields.addAll(value.fieldIds());
      value =
          new RecordAggregateReadRequirement(
              value.modelId(), value.recordId(), List.copyOf(fields));
    }
    values.put(value.rowIdentity(), value);
    if (values.size() > 206) {
      throw invalid();
    }
  }

  private static void mergeExternal(
      Map<List<String>, RecordReferenceReadRequirement> values,
      RecordReferenceReadRequirement value) {
    var previous = values.get(value.rowIdentity());
    if (previous != null) {
      var fields = new LinkedHashSet<>(previous.fieldIds());
      fields.addAll(value.fieldIds());
      value =
          new RecordReferenceReadRequirement(
              value.modelId(),
              value.appId(),
              value.formId(),
              value.resourceCode(),
              value.recordId(),
              value.detailModelId(),
              value.detailRecordId(),
              List.copyOf(fields));
    }
    values.put(value.rowIdentity(), value);
    if (values.size() > 256) {
      throw invalid();
    }
  }

  private static org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException invalid() {
    return RecordReferenceSnapshotProvenance.invalid();
  }

  private record Input(RuntimeRecordModel model, String id, String code) {}
}
