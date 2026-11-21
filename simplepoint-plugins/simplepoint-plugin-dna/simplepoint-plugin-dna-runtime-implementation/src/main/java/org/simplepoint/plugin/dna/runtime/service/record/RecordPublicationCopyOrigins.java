package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/**
 * Publication staging freezes value changes and their original permission dependencies together.
 */
final class RecordPublicationCopyOrigins {
  private RecordPublicationCopyOrigins() {}

  static RecordPublicationAggregate attach(
      RecordPublicationIntent intent,
      RuntimeRecordModel parent,
      Map<String, RuntimeRecordModel> details,
      Map<String, Set<String>> dependencies,
      Map<String, List<RecordView>> before,
      OwnedRecordView after,
      List<RecordPublicationAggregate.Row> patches,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (intent.queryProtection().isEmpty()) {
      return new RecordPublicationAggregate(
          1,
          after.parent().id(),
          patches.stream()
              .filter(row -> row.parentRecordId() == null || !row.changes().isEmpty())
              .toList());
    }
    String parentModel = parent.physicalModelId();
    Map<String, List<RecordView>> rows = new LinkedHashMap<>(after.details());
    rows.put(parentModel, List.of(after.parent()));
    var state =
        RecordReferenceCopyState.capture(
            parentModel, intent.key().tenantId(), before, rows, reader);
    var policy =
        new RecordCalculationPolicy(
            1,
            intent.command().appId(),
            intent.command().formId(),
            intent.command().schemaReference(),
            intent.command().calculation(),
            intent.queryProtection());
    var binding =
        new RecordCalculationWriteSupport.Binding(
            new RecordCalculationStore.Installed(intent.command().targetVersion(), policy),
            parent,
            details,
            dependencies,
            null);
    Map<List<String>, RecordReferenceCopySnapshot> computed = new LinkedHashMap<>();
    var captured = RecordDerivedCopyRequirements.capture(binding, after, state.prospective());
    RecordReferenceCopyBudget.validate(parentModel, captured, intent.queryProtection());
    captured.forEach(
        snapshot ->
            computed.put(
                List.of(
                    snapshot.detailModelId() == null ? parentModel : snapshot.detailModelId(),
                    snapshot.sourceRecordId()),
                snapshot));
    Map<List<String>, RecordPublicationCopyChange> origins = new LinkedHashMap<>();
    Set<List<String>> changed = new java.util.LinkedHashSet<>();
    for (var row : patches) {
      var identity = List.of(row.modelId(), row.recordId());
      var old = state.previous().get(identity);
      var output = computed.get(identity);
      if (old == null && output == null) {
        continue;
      }
      var candidate =
          output == null
              ? new RecordReferenceCopyStore.Stored(row.previousVersion() + 1, List.of())
              : new RecordReferenceCopyStore.Stored(
                  output.sourceVersion(), output.origins(), output.derivedOrigins());
      origins.put(identity, new RecordPublicationCopyChange(old, candidate));
      if (old == null
          || !old.origins().equals(candidate.origins())
          || !old.derivedOrigins().equals(candidate.derivedOrigins())) {
        changed.add(identity);
      }
    }
    List<RecordPublicationAggregate.Row> selected = new ArrayList<>();
    for (var row : patches) {
      var identity = List.of(row.modelId(), row.recordId());
      if (row.parentRecordId() == null || !row.changes().isEmpty() || changed.contains(identity)) {
        selected.add(row);
      }
    }
    boolean writing =
        selected.size() > 1
            || !selected.getFirst().changes().isEmpty()
            || changed.contains(List.of(parentModel, after.parent().id()));
    List<RecordPublicationAggregate.Row> result = new ArrayList<>();
    for (var row : selected) {
      var proof = writing ? origins.get(List.of(row.modelId(), row.recordId())) : null;
      result.add(
          new RecordPublicationAggregate.Row(
              row.modelId(),
              row.recordId(),
              row.parentRecordId(),
              row.previousVersion(),
              row.previousUpdatedBy(),
              row.previousUpdatedAt(),
              row.changes(),
              proof));
    }
    return new RecordPublicationAggregate(
        result.stream().anyMatch(row -> row.copyChange() != null) ? 2 : 1,
        after.parent().id(),
        result);
  }
}
