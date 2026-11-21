package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/**
 * Pure authorization intersection for copied values and their formula consumers. Target checks are
 * supplied by the read owner after it closes the source transaction; no target values enter this
 * projector and no source field grant is widened.
 */
final class RecordReferenceCopyProjection {
  private final String parentModelId;
  private final Map<String, Map<String, String>> codes;
  private final Map<Field, Set<Field>> dependencies;

  RecordReferenceCopyProjection(
      RuntimeRecordModel parent,
      List<RuntimeRecordModel> children,
      Map<Field, Set<Field>> dependencies) {
    this.parentModelId = parent.physicalModelId();
    Map<String, Map<String, String>> indexed = new LinkedHashMap<>();
    var models = new ArrayList<>(children);
    models.add(parent);
    for (var model : models) {
      Map<String, String> fields = new LinkedHashMap<>();
      model
          .requestedSchema()
          .fields()
          .forEach(
              field -> {
                if (fields.putIfAbsent(field.fieldId(), field.code()) != null) {
                  throw RecordReferenceSnapshotProvenance.invalid();
                }
              });
      if (indexed.putIfAbsent(model.physicalModelId(), Map.copyOf(fields)) != null) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
    this.codes = Map.copyOf(indexed);
    Map<Field, Set<Field>> graph = new LinkedHashMap<>();
    dependencies.forEach((field, inputs) -> graph.put(field, Set.copyOf(inputs)));
    if (graph.size() > 256 || graph.values().stream().mapToInt(Set::size).sum() > 65_536) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    this.dependencies = Map.copyOf(graph);
  }

  /** Hidden and masked source values must not cause a target authorization read. */
  Set<RecordReferenceCopyOrigin> visibleOrigins(
      OwnedRecordView source, List<RecordReferenceCopySnapshot> origins) {
    var rows = rows(source);
    Set<RecordReferenceCopyOrigin> required = new LinkedHashSet<>();
    for (var captured : origins) {
      String model = model(captured);
      var row = rows.get(new Row(model, captured.sourceRecordId()));
      if (row == null) {
        continue;
      }
      requireVersion(captured, row);
      for (var origin : captured.origins()) {
        String code = code(new Field(model, origin.destinationFieldId()));
        if (code != null
            && row.values().containsKey(code)
            && !"***".equals(row.values().get(code))) {
          required.add(origin);
        }
      }
    }
    return Set.copyOf(required);
  }

  Set<RecordDerivedCopyOrigin> visibleDerived(
      OwnedRecordView source, List<RecordReferenceCopySnapshot> origins) {
    var rows = rows(source);
    Set<RecordDerivedCopyOrigin> required = new LinkedHashSet<>();
    for (var captured : origins) {
      String model = model(captured);
      var row = rows.get(new Row(model, captured.sourceRecordId()));
      if (row == null) {
        continue;
      }
      requireVersion(captured, row);
      for (var origin : captured.derivedOrigins()) {
        String code = code(new Field(model, origin.destinationFieldId()));
        if (code != null
            && row.values().containsKey(code)
            && !"***".equals(row.values().get(code))) {
          required.add(origin);
        }
      }
    }
    return Set.copyOf(required);
  }

  /** Uses the compiled formula graph; callers never infer dependencies from expression text. */
  static Map<Field, Set<Field>> dependencies(List<RecordCalculationWriteSupport.Binding> bindings) {
    Map<Field, Set<Field>> result = new LinkedHashMap<>();
    for (var binding : bindings) {
      Map<String, RuntimeRecordModel> scopes = new LinkedHashMap<>(binding.details());
      if (scopes.putIfAbsent("parent", binding.parent()) != null) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
      binding
          .dependencies()
          .forEach(
              (output, inputs) -> {
                var sources =
                    result.computeIfAbsent(
                        resolve(scopes, output), ignored -> new LinkedHashSet<>());
                inputs.forEach(input -> sources.add(resolve(scopes, input)));
              });
    }
    Map<Field, Set<Field>> immutable = new LinkedHashMap<>();
    result.forEach((output, inputs) -> immutable.put(output, Set.copyOf(inputs)));
    return Map.copyOf(immutable);
  }

  private static Field resolve(Map<String, RuntimeRecordModel> scopes, String reference) {
    int separator = reference.indexOf('.');
    var model = separator < 1 ? null : scopes.get(reference.substring(0, separator));
    String field = separator < 1 ? "" : reference.substring(separator + 1);
    if (model == null
        || model.requestedSchema().fields().stream()
            .noneMatch(value -> value.fieldId().equals(field))) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    return new Field(model.physicalModelId(), field);
  }

  OwnedRecordView project(
      OwnedRecordView source,
      List<RecordReferenceCopySnapshot> origins,
      Set<RecordReferenceCopyOrigin> authorized) {
    return project(source, origins, authorized, Set.of());
  }

  OwnedRecordView project(
      OwnedRecordView source,
      List<RecordReferenceCopySnapshot> origins,
      Set<RecordReferenceCopyOrigin> authorized,
      Set<RecordDerivedCopyOrigin> authorizedDerived) {
    var rows = rows(source);
    Map<Row, Set<Field>> denied = new LinkedHashMap<>();
    for (var captured : origins) {
      String model = model(captured);
      var key = new Row(model, captured.sourceRecordId());
      var row = rows.get(key);
      if (row != null) {
        requireVersion(captured, row);
      }
      for (var origin : captured.origins()) {
        var field = new Field(model, origin.destinationFieldId());
        String code = code(field);
        if (!authorized.contains(origin)
            || row == null
            || code == null
            || !row.values().containsKey(code)
            || "***".equals(row.values().get(code))) {
          denied.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(field);
        }
      }
    }
    for (var captured : origins) {
      var key = new Row(model(captured), captured.sourceRecordId());
      for (var origin : captured.derivedOrigins()) {
        var row = rows.get(key);
        String code = code(new Field(key.modelId(), origin.destinationFieldId()));
        if (!authorizedDerived.contains(origin)
            || row == null
            || code == null
            || !row.values().containsKey(code)
            || "***".equals(row.values().get(code))) {
          denied
              .computeIfAbsent(key, ignored -> new LinkedHashSet<>())
              .add(new Field(key.modelId(), origin.destinationFieldId()));
        }
      }
    }
    // A work queue visits each denied row/field once; never repeatedly scan every origin for
    // every formula. Same-model dependencies remain row-local, aggregate dependencies do not.
    Map<Field, Set<Field>> consumers = new LinkedHashMap<>();
    dependencies.forEach(
        (output, inputs) ->
            inputs.forEach(
                input ->
                    consumers
                        .computeIfAbsent(input, ignored -> new LinkedHashSet<>())
                        .add(output)));
    Map<String, List<Row>> rowsByModel = new LinkedHashMap<>();
    rows.keySet()
        .forEach(
            row ->
                rowsByModel.computeIfAbsent(row.modelId(), ignored -> new ArrayList<>()).add(row));
    var pending = new ArrayDeque<Cell>();
    denied.forEach((row, fields) -> fields.forEach(field -> pending.add(new Cell(row, field))));
    while (!pending.isEmpty()) {
      var input = pending.removeFirst();
      for (var output : consumers.getOrDefault(input.field(), Set.of())) {
        var destinations =
            output.modelId().equals(input.row().modelId())
                ? List.of(input.row())
                : rowsByModel.getOrDefault(output.modelId(), List.of());
        for (var destination : destinations) {
          if (denied.computeIfAbsent(destination, ignored -> new LinkedHashSet<>()).add(output)) {
            pending.add(new Cell(destination, output));
          }
        }
      }
    }
    Map<Row, RecordView> projected = new LinkedHashMap<>();
    rows.forEach(
        (key, value) -> {
          var fields = new LinkedHashMap<>(value.values());
          denied.getOrDefault(key, Set.of()).forEach(field -> fields.remove(code(field)));
          projected.put(key, new RecordView(value.id(), value.version(), fields));
        });
    Map<String, List<RecordView>> details = new LinkedHashMap<>();
    source
        .details()
        .forEach(
            (model, values) ->
                details.put(
                    model,
                    values.stream().map(row -> projected.get(new Row(model, row.id()))).toList()));
    var labels =
        source.referenceSnapshots().stream()
            .filter(
                label -> {
                  var row = projected.get(new Row(label.sourceModelId(), label.sourceRecordId()));
                  String code = code(new Field(label.sourceModelId(), label.sourceFieldId()));
                  return row != null
                      && code != null
                      && row.values().containsKey(code)
                      && row.values().get(code) != null
                      && !"***".equals(row.values().get(code));
                })
            .toList();
    return new OwnedRecordView(
        projected.get(new Row(parentModelId, source.parent().id())), details, labels);
  }

  private String code(Field field) {
    return codes.getOrDefault(field.modelId(), Map.of()).get(field.fieldId());
  }

  private String model(RecordReferenceCopySnapshot origin) {
    return origin.detailModelId() == null ? parentModelId : origin.detailModelId();
  }

  private Map<Row, RecordView> rows(OwnedRecordView source) {
    Map<Row, RecordView> rows = new LinkedHashMap<>();
    rows.put(new Row(parentModelId, source.parent().id()), source.parent());
    source
        .details()
        .forEach(
            (model, values) ->
                values.forEach(
                    row -> {
                      if (!codes.containsKey(model)
                          || rows.putIfAbsent(new Row(model, row.id()), row) != null) {
                        throw RecordReferenceSnapshotProvenance.invalid();
                      }
                    }));
    if (rows.size() > 201) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    return rows;
  }

  private static void requireVersion(RecordReferenceCopySnapshot origin, RecordView row) {
    if (origin.sourceVersion() != row.version()) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
  }

  record Field(String modelId, String fieldId) {}

  private record Row(String modelId, String recordId) {}

  private record Cell(Row row, Field field) {}
}
