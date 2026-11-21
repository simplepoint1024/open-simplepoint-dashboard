package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/** Replaces only confirmed copied inputs before deriving the same evaluation's output proof. */
final class RecordReferenceCopiedInputs {
  private static final StandardLogicalValueConverter VALUES = new StandardLogicalValueConverter();

  private RecordReferenceCopiedInputs() {}

  static List<RecordReferenceCopySnapshot> merge(
      RecordCalculationWriteSupport.Binding binding,
      OwnedRecordView after,
      List<RecordReferenceCopySnapshot> retained,
      Map<List<String>, RecordReferenceCopyEffects.Applied> changes) {
    if (changes.isEmpty()) {
      return List.copyOf(retained);
    }
    if (changes.size() > 201) {
      throw RecordReferenceAdmission.incompatible();
    }
    String parent = binding.parent().physicalModelId();
    Map<String, RuntimeRecordModel> models = new LinkedHashMap<>();
    models.put(parent, binding.parent());
    binding.details().values().forEach(model -> models.put(model.physicalModelId(), model));
    Map<List<String>, RecordView> rows = new LinkedHashMap<>();
    rows.put(List.of(parent, after.parent().id()), after.parent());
    after
        .details()
        .forEach(
            (model, values) ->
                values.forEach(
                    row -> {
                      if (rows.putIfAbsent(List.of(model, row.id()), row) != null) {
                        throw RecordReferenceAdmission.incompatible();
                      }
                    }));
    Map<List<String>, RecordReferenceCopySnapshot> previous = new LinkedHashMap<>();
    for (var snapshot : retained) {
      String model = snapshot.detailModelId() == null ? parent : snapshot.detailModelId();
      var key = List.of(model, snapshot.sourceRecordId());
      var row = rows.get(key);
      if (row == null
          || row.version() != snapshot.sourceVersion()
          || previous.putIfAbsent(key, snapshot) != null) {
        throw RecordReferenceAdmission.incompatible();
      }
    }
    for (var entry : changes.entrySet()) {
      var key = entry.getKey();
      var row = rows.get(key);
      var model = key.size() != 2 ? null : models.get(key.getFirst());
      if (row == null || model == null || entry.getValue() == null) {
        throw RecordReferenceAdmission.incompatible();
      }
      var applied = entry.getValue();
      for (var value : applied.values().entrySet()) {
        var field =
            model.activeSchema().fields().stream()
                .filter(definition -> definition.fieldId().equals(value.getKey()))
                .findFirst()
                .orElseThrow(RecordReferenceAdmission::incompatible);
        if (!row.values().containsKey(field.code())
            || !VALUES.normalize(field, row.values().get(field.code())).equals(value.getValue())) {
          throw RecordReferenceAdmission.incompatible();
        }
      }
      var old = previous.get(key);
      Map<String, RecordReferenceCopyOrigin> before = new LinkedHashMap<>();
      if (old != null) {
        old.origins().forEach(origin -> before.put(origin.destinationFieldId(), origin));
      }
      var checked = new RecordReferenceCopyStore.Stored(row.version(), applied.origins());
      Map<String, RecordReferenceCopyOrigin> copied = new LinkedHashMap<>();
      checked.origins().forEach(origin -> copied.put(origin.destinationFieldId(), origin));
      var fields = new java.util.LinkedHashSet<>(before.keySet());
      fields.addAll(copied.keySet());
      for (String field : fields) {
        // Updating a ledger without rewriting its destination could relabel an older value as a
        // newer, authorized copy. Explicit refresh still includes the value even when equal.
        if (!Objects.equals(before.get(field), copied.get(field))
            && !applied.values().containsKey(field)) {
          throw RecordReferenceAdmission.incompatible();
        }
      }
      var derived =
          old == null
              ? List.<RecordDerivedCopyOrigin>of()
              : old.derivedOrigins().stream()
                  .filter(origin -> !applied.values().containsKey(origin.destinationFieldId()))
                  .toList();
      if (checked.origins().isEmpty() && derived.isEmpty()) {
        previous.remove(key);
      } else {
        previous.put(
            key,
            new RecordReferenceCopySnapshot(
                key.getFirst().equals(parent) ? null : key.getFirst(),
                row.id(),
                row.version(),
                checked.origins(),
                derived));
      }
    }
    return List.copyOf(previous.values());
  }
}
