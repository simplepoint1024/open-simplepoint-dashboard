package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Prepared target projection; capture only uses the caller-owned physical transaction. */
final class RecordReferenceTargetRead {
  private final RecordReferenceAdmission.Target target;
  private final RecordCalculationWriteSupport.Binding binding;
  private final Map<String, Set<String>> selected;
  private final Map<String, Scope> effective;
  private final Set<String> fields;
  private final RecordSqlCompiler compiler;
  private final PhysicalSchemaCodec schemas;

  RecordReferenceTargetRead(
      RecordReferenceAdmission.Target target,
      RecordCalculationWriteSupport.Binding binding,
      Map<String, Set<String>> selected,
      Map<String, Scope> effective,
      Set<String> fields,
      RecordSqlCompiler compiler,
      PhysicalSchemaCodec schemas) {
    this.target = target;
    this.binding = binding;
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    selected.forEach((name, values) -> copy.put(name, Set.copyOf(values)));
    this.selected = Map.copyOf(copy);
    this.effective = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(effective));
    this.fields = Set.copyOf(fields);
    this.compiler = compiler;
    this.schemas = schemas;
  }

  List<RuntimeRecordModel> children() {
    return effective.values().stream()
        .filter(scope -> scope.owner() != null)
        .map(Scope::model)
        .toList();
  }

  Map<String, RecordReferenceMappingTargets.Captured> capture(
      Set<String> ids,
      java.util.function.Function<
              RecordSelectPlan, org.simplepoint.data.calcite.core.query.CalciteQueryResult>
          reader) {
    if (ids.isEmpty() || ids.size() > 100) {
      throw conflict();
    }
    Map<String, List<RecordView>> records = new LinkedHashMap<>();
    int count = 0;
    for (var entry : effective.entrySet()) {
      var rows = rows(entry.getValue(), ids, selected.get(entry.getKey()), reader);
      count += rows.size();
      if (count > 500) {
        throw conflict();
      }
      records.put(entry.getValue().model().physicalModelId(), rows);
    }
    var roots = records.get(target.model().physicalModelId());
    if (!roots.stream()
        .map(RecordView::id)
        .collect(java.util.stream.Collectors.toSet())
        .equals(ids)) {
      throw conflict();
    }
    var origins =
        fields.isEmpty()
                || target.snapshot().calculation() == null
                || target.snapshot().calculation().policy().queryProtection().isEmpty()
            ? List.<RecordReferenceCopySnapshot>of()
            : RecordReferenceCopyState.current(
                    target.model().physicalModelId(),
                    target.access().actor().tenantId(),
                    records,
                    reader)
                .prospective();
    Map<String, RecordReferenceMappingTargets.Captured> result = new LinkedHashMap<>();
    int requirementCount = 0;
    for (var root : roots) {
      Map<String, List<RecordView>> details = new LinkedHashMap<>();
      effective.values().stream()
          .filter(scope -> scope.owner() != null)
          .forEach(
              scope -> {
                var owned =
                    records.get(scope.model().physicalModelId()).stream()
                        .filter(row -> root.id().equals(row.values().get(scope.owner())))
                        .toList();
                if (owned.size() > 40) {
                  throw conflict();
                }
                details.put(scope.model().physicalModelId(), owned);
              });
      var values = new OwnedRecordView(root, details);
      var proof =
          origins.stream()
              .filter(
                  origin ->
                      origin.detailModelId() == null
                          ? root.id().equals(origin.sourceRecordId())
                          : details.getOrDefault(origin.detailModelId(), List.of()).stream()
                              .anyMatch(row -> row.id().equals(origin.sourceRecordId())))
              .toList();
      var requirements =
          fields.isEmpty()
              ? Map.<String, List<RecordReferenceReadRequirement>>of()
              : RecordReferenceCopyRequirements.capture(
                  target.model(), binding, values, proof, target.access().resourceCode(), fields);
      requirementCount += requirements.values().stream().mapToInt(List::size).sum();
      if (requirementCount > 16384) {
        throw conflict();
      }
      result.put(
          root.id(),
          new RecordReferenceMappingTargets.Captured(
              project(target.model(), root, fields), requirements));
    }
    // Independent child writes are forbidden: the root version fences every input and
    // ledger
    // read, including vertical projections split by the standard 100-field query budget.
    var versions = rows(effective.get("parent"), ids, Set.of(), reader);
    Map<String, Long> expected = new LinkedHashMap<>();
    roots.forEach(row -> expected.put(row.id(), row.version()));
    if (versions.size() != expected.size()
        || versions.stream()
            .anyMatch(row -> !Objects.equals(expected.get(row.id()), row.version()))) {
      throw conflict();
    }
    return Map.copyOf(result);
  }

  private static RecordView project(RuntimeRecordModel model, RecordView row, Set<String> fields) {
    Map<String, Object> values = new LinkedHashMap<>();
    model.requestedSchema().fields().stream()
        .filter(field -> fields.contains(field.fieldId()))
        .forEach(field -> values.put(field.code(), row.values().get(field.code())));
    return new RecordView(row.id(), row.version(), values);
  }

  private List<RecordView> rows(
      Scope scope,
      Set<String> ids,
      Set<String> fields,
      java.util.function.Function<
              RecordSelectPlan, org.simplepoint.data.calcite.core.query.CalciteQueryResult>
          reader) {
    var model = scope.model();
    Set<String> codes = new LinkedHashSet<>();
    for (var field : fields) {
      codes.add(
          model.requestedSchema().fields().stream()
              .filter(value -> value.fieldId().equals(field))
              .findFirst()
              .orElseThrow(RecordReferenceTargetRead::conflict)
              .code());
    }
    codes.add("id");
    if (scope.owner() != null) {
      codes.add(scope.owner());
    }
    if (!scope.access().readableFields().containsAll(codes)
        || codes.stream().anyMatch(scope.access().maskedFields()::contains)) {
      throw conflict();
    }
    var columns = codes.stream().sorted().toList();
    Map<String, RecordView> combined = new LinkedHashMap<>();
    var filter =
        new RecordFilter(
            RecordFilterOperator.IN,
            scope.owner() == null ? "id" : scope.owner(),
            new ArrayList<>(ids),
            List.of());
    if (scope.owner() != null
        && RecordQueryServiceImpl.countValue(
                reader.apply(compiler.count(model, scope.access(), filter)))
            > 500) {
      throw conflict();
    }
    for (int offset = 0; offset < columns.size(); offset += 100) {
      var query =
          new RecordPageQuery(
              model.physicalModelId(),
              new RecordSchemaReference(
                  model.requestedVersion(), schemas.encode(model.requestedSchema()).checksum()),
              0,
              500,
              columns.subList(offset, Math.min(offset + 100, columns.size())),
              filter,
              List.of(new RecordSort("id", RecordSortDirection.ASC)));
      var plan = compiler.page(model, scope.access(), query);
      var raw = reader.apply(plan);
      if (raw.truncated()) {
        throw conflict();
      }
      var batch = RecordQueryServiceImpl.views(raw, plan, model, scope.access());
      if (batch.stream().map(RecordView::id).distinct().count() != batch.size()) {
        throw conflict();
      }
      if (offset > 0
          && !new ArrayList<>(combined.keySet())
              .equals(batch.stream().map(RecordView::id).toList())) {
        throw conflict();
      }
      for (var row : batch) {
        var old = combined.get(row.id());
        if (old != null && old.version() != row.version()) {
          throw conflict();
        }
        var values = new LinkedHashMap<>(row.values());
        if (old != null) {
          values.putAll(old.values());
        }
        combined.put(row.id(), new RecordView(row.id(), row.version(), values));
      }
    }
    if (scope.owner() != null
        && combined.values().stream()
            .anyMatch(row -> !ids.contains(row.values().get(scope.owner())))) {
      throw conflict();
    }
    return List.copyOf(combined.values());
  }

  record Scope(RuntimeRecordModel model, RecordAccessContext access, String owner) {}

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Reference input, policy or original permission dependencies changed");
  }
}
