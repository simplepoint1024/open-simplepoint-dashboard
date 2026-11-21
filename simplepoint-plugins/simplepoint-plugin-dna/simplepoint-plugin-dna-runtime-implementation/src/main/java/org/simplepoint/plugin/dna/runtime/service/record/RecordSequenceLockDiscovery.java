package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.record.RecordCalculationWriteSupport.Scope;

/** Discovers every possible reference without evaluating formulas against stale preceding steps. */
final class RecordSequenceLockDiscovery {
  private RecordSequenceLockDiscovery() {}

  static RecordSequenceLockPlan discover(
      Scope parent,
      Map<String, Scope> children,
      List<RecordSequenceLockPlan.Intent> intents,
      Rows reader) {
    if (intents.isEmpty() || intents.size() > 100) {
      throw invalid();
    }
    var sources = new LinkedHashSet<RecordSequenceLockPlan.Source>();
    var references = new LinkedHashSet<RecordSequenceLockPlan.Reference>();
    var snapshots = new LinkedHashMap<Selector, Snapshot>();
    var seen = new LinkedHashSet<String>();
    for (var intent : intents) {
      sources.add(
          new RecordSequenceLockPlan.Source(parent.model().physicalModelId(), intent.recordId()));
      if (seen.add(intent.recordId()) && !(intent.mutation() instanceof RecordCreateMutation)) {
        var root = capture(parent, intent.recordId(), true, reader, snapshots);
        if (root.size() != 1 || !root.getFirst().id().equals(intent.recordId())) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the authorized scope");
        }
        references(parent.model(), root.getFirst().values(), references);
        for (var child : children.values()) {
          for (var row : capture(child, intent.recordId(), false, reader, snapshots)) {
            sources.add(
                new RecordSequenceLockPlan.Source(child.model().physicalModelId(), row.id()));
            references(child.model(), row.values(), references);
          }
        }
      }
      var values =
          intent.mutation() instanceof RecordCreateMutation create
              ? create.values()
              : intent.mutation() instanceof RecordUpdateMutation update
                  ? update.values()
                  : Map.<String, Object>of();
      references(
          parent.model(),
          RecordCalculationWriteSupport.physical(parent.model(), values),
          references);
      if (sources.size() > 10_451 || references.size() > 10_050) {
        throw invalid();
      }
    }
    return new RecordSequenceLockPlan(
        List.copyOf(sources),
        List.copyOf(references),
        () -> {
          for (var snapshot : snapshots.values()) {
            var actual =
                reader.read(
                    snapshot.scope(), snapshot.selector().id(), snapshot.selector().parent());
            if (!versions(actual).equals(snapshot.versions())) {
              throw new DnaRuntimeException(
                  DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
                  "Record sequence inputs changed while acquiring transaction locks");
            }
          }
        });
  }

  private static List<RecordView> capture(
      Scope scope, String id, boolean parent, Rows reader, Map<Selector, Snapshot> snapshots) {
    var rows = reader.read(scope, id, parent);
    var selector = new Selector(scope.model().physicalModelId(), id, parent);
    snapshots.put(selector, new Snapshot(scope, selector, versions(rows)));
    return rows;
  }

  private static List<Version> versions(List<RecordView> rows) {
    return rows.stream().map(row -> new Version(row.id(), row.version())).toList();
  }

  private static void references(
      RuntimeRecordModel model,
      Map<String, Object> values,
      java.util.Set<RecordSequenceLockPlan.Reference> references) {
    for (var reference : model.activeSchema().references()) {
      String column =
          model.activeSchema().fields().stream()
              .filter(field -> field.fieldId().equals(reference.fieldId()))
              .findFirst()
              .orElseThrow(RecordReferenceAdmission::incompatible)
              .code();
      String id = RecordReferenceRules.identity(values.get(column));
      if (id != null) {
        references.add(
            new RecordSequenceLockPlan.Reference(
                model.physicalModelId(), reference.relationId(), id));
      }
    }
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
        "Record sequence exceeds its transaction-wide row-lock discovery budget");
  }

  @FunctionalInterface
  interface Rows {
    List<RecordView> read(Scope scope, String id, boolean parent);
  }

  private record Selector(String modelId, String id, boolean parent) {}

  private record Version(String id, long version) {}

  private record Snapshot(Scope scope, Selector selector, List<Version> versions) {}
}
