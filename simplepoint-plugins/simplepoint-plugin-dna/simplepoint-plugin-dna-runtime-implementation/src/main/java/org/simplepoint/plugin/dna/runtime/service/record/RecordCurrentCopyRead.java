package org.simplepoint.plugin.dna.runtime.service.record;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Detached current records: source ledgers are batched in-transaction, target checks run later. */
final class RecordCurrentCopyRead {
  private final RuntimeRecordModel parent;
  private final RecordAccessContext access;
  private final List<OwnedRecordView> values;
  private final Map<String, List<RecordReferenceCopySnapshot>> origins;
  private final RecordReferenceCopyProjection projection;

  private RecordCurrentCopyRead(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      List<RuntimeRecordModel> children,
      List<OwnedRecordView> values,
      Map<String, List<RecordReferenceCopySnapshot>> origins) {
    this.parent = parent;
    this.access = access;
    this.values = List.copyOf(values);
    this.origins = Map.copyOf(origins);
    this.projection = new RecordReferenceCopyProjection(parent, children, Map.of());
  }

  static RecordCurrentCopyRead capture(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      List<RuntimeRecordModel> children,
      List<OwnedRecordView> values,
      RecordCalculationStore.Installed installed,
      Function<RecordSelectPlan, CalciteQueryResult> query) {
    if (values.size() > 500) {
      throw limited();
    }
    // The publication gate guarantees new mapping writers have durable query protection.
    // Ordinary legacy forms need no additional ledger query.
    if (installed == null || installed.policy().queryProtection().isEmpty() || values.isEmpty()) {
      return new RecordCurrentCopyRead(parent, access, children, values, Map.of());
    }
    Map<List<String>, String> owners = new LinkedHashMap<>();
    Map<String, List<RecordView>> rows = new LinkedHashMap<>();
    for (var aggregate : values) {
      add(rows, owners, parent.physicalModelId(), aggregate.parent(), aggregate.parent().id());
      aggregate
          .details()
          .forEach(
              (model, records) ->
                  records.forEach(row -> add(rows, owners, model, row, aggregate.parent().id())));
    }
    if (owners.size() > 500 || rows.size() > 6) {
      throw limited();
    }
    var store = new RecordReferenceCopyStore();
    Map<String, List<RecordReferenceCopySnapshot>> captured = new LinkedHashMap<>();
    int bytes = 0;
    int count = 0;
    for (var entry : rows.entrySet()) {
      for (int offset = 0; offset < entry.getValue().size(); offset += 100) {
        var batch =
            entry.getValue().subList(offset, Math.min(offset + 100, entry.getValue().size()));
        var keys =
            batch.stream()
                .map(
                    row ->
                        new RecordReferenceCopyStore.Key(
                            access.actor().tenantId(), entry.getKey(), row.id()))
                .toList();
        var result = query.apply(store.lookupBatch(keys));
        for (var row : result.rows()) {
          if (row.size() != 6 || !(row.get(5) instanceof String json)) {
            throw RecordReferenceSnapshotProvenance.invalid();
          }
          bytes += json.getBytes(StandardCharsets.UTF_8).length;
          if (bytes > 8 * 1024 * 1024) {
            throw limited();
          }
        }
        var stored = store.readBatch(keys, result);
        for (var row : batch) {
          var proof = stored.get(row.id());
          if (proof == null) {
            continue;
          }
          if (proof.sourceVersion() != row.version()) {
            throw RecordReferenceSnapshotProvenance.invalid();
          }
          count += proof.origins().size() + proof.derivedOrigins().size();
          if (count > 10050) {
            throw limited();
          }
          if (!proof.origins().isEmpty() || !proof.derivedOrigins().isEmpty()) {
            String owner = owners.get(List.of(entry.getKey(), row.id()));
            captured
                .computeIfAbsent(owner, ignored -> new ArrayList<>())
                .add(
                    new RecordReferenceCopySnapshot(
                        entry.getKey().equals(parent.physicalModelId()) ? null : entry.getKey(),
                        row.id(),
                        row.version(),
                        proof.origins(),
                        proof.derivedOrigins()));
          }
        }
      }
    }
    captured.replaceAll((id, proofs) -> List.copyOf(proofs));
    return new RecordCurrentCopyRead(parent, access, children, values, captured);
  }

  List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget> targets(
      RecordReferenceCopyAuthorization authorization, RecordExecutionDeadline deadline) {
    Set<RecordReferenceReadRequirement> required = new LinkedHashSet<>();
    Map<String, Set<RecordDerivedCopyOrigin>> derived = new LinkedHashMap<>();
    for (var value : values) {
      var proof = origins.getOrDefault(value.parent().id(), List.of());
      projection
          .visibleOrigins(value, proof)
          .forEach(origin -> required.addAll(origin.requirements()));
      derived.put(value.parent().id(), projection.visibleDerived(value, proof));
    }
    authorization
        .readableDerivedBatch(parent, access, derived, deadline)
        .values()
        .forEach(origins -> origins.forEach(origin -> required.addAll(origin.requirements())));
    if (required.size() > 16384) {
      throw limited();
    }
    var targets =
        required.stream()
            .map(
                requirement ->
                    new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget(
                        requirement.modelId(),
                        requirement.appId(),
                        requirement.formId(),
                        requirement.resourceCode()))
            .distinct()
            .sorted(
                java.util.Comparator.comparing(
                        org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget
                            ::resourceCode)
                    .thenComparing(
                        org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget
                            ::modelId))
            .toList();
    if (targets.size() > 50) {
      throw limited();
    }
    deadline.remainingMillis();
    return targets;
  }

  List<OwnedRecordView> authorize(
      RecordReferenceCopyAuthorization authorization, RecordExecutionDeadline deadline) {
    if (origins.isEmpty()) {
      return values;
    }
    Map<String, Set<RecordReferenceCopyOrigin>> copies = new LinkedHashMap<>();
    Map<String, Set<RecordDerivedCopyOrigin>> derived = new LinkedHashMap<>();
    for (var value : values) {
      var proof = origins.getOrDefault(value.parent().id(), List.of());
      copies.put(value.parent().id(), projection.visibleOrigins(value, proof));
      derived.put(value.parent().id(), projection.visibleDerived(value, proof));
    }
    var local = authorization.readableDerivedBatch(parent, access, derived, deadline);
    Set<RecordReferenceReadRequirement> required = new LinkedHashSet<>();
    copies
        .values()
        .forEach(origins -> origins.forEach(origin -> required.addAll(origin.requirements())));
    local
        .values()
        .forEach(origins -> origins.forEach(origin -> required.addAll(origin.requirements())));
    if (required.size() > 16384) {
      throw limited();
    }
    var allowed = authorization.authorizeRequirements(access.actor(), required, deadline);
    return values.stream()
        .map(
            value -> {
              String id = value.parent().id();
              var copyGrants =
                  copies.get(id).stream()
                      .filter(origin -> allowed.containsAll(origin.requirements()))
                      .collect(java.util.stream.Collectors.toUnmodifiableSet());
              var derivedGrants =
                  local.get(id).stream()
                      .filter(origin -> allowed.containsAll(origin.requirements()))
                      .collect(java.util.stream.Collectors.toUnmodifiableSet());
              return projection.project(
                  value, origins.getOrDefault(id, List.of()), copyGrants, derivedGrants);
            })
        .toList();
  }

  private static void add(
      Map<String, List<RecordView>> rows,
      Map<List<String>, String> owners,
      String model,
      RecordView row,
      String owner) {
    if (owners.putIfAbsent(List.of(model, row.id()), owner) != null) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    rows.computeIfAbsent(model, ignored -> new ArrayList<>()).add(row);
  }

  private static DnaRuntimeException limited() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_QUERY_INVALID,
        "Reference projection exceeds its bounded proof budget; reduce the requested page size");
  }
}
