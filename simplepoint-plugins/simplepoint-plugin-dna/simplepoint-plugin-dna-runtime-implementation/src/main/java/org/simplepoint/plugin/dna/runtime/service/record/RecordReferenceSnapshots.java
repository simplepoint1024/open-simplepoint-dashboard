package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Captures labels through the authorized borrowed transaction after reference row locks. */
final class RecordReferenceSnapshots {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private RecordReferenceSnapshots() {}

  static OwnedRecordView capture(
      OwnedRecordView snapshot,
      RecordWritePreparation prepared,
      RecordReferenceAdmission.Plan plan,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return captureWithOrigins(snapshot, prepared, plan, compiler, reader).values();
  }

  static Captured captureWithOrigins(
      OwnedRecordView snapshot,
      RecordWritePreparation prepared,
      RecordReferenceAdmission.Plan plan,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var rows = new LinkedHashMap<String, List<RecordView>>(snapshot.details());
    var parent =
        prepared.snapshot().parts().stream()
            .filter(RecordSnapshotPlan.Part::parent)
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible);
    rows.put(parent.model().physicalModelId(), List.of(snapshot.parent()));
    var locked = new LinkedHashMap<List<String>, RecordReferenceCheck>();
    for (var check : prepared.references()) {
      var key = List.of(check.relationId(), check.sourceModelId(), check.sourceRecordId());
      if (locked.putIfAbsent(key, check) != null) {
        throw RecordReferenceAdmission.incompatible();
      }
    }
    var result = new ArrayList<RecordReferenceSnapshot>();
    var origins = new ArrayList<RecordReferenceLabelOrigin>();
    int requirementCount = 0;
    long bytes = 2;
    // Canonical relation order makes immutable snapshots independent of map iteration order.
    for (var relationId : plan.bindings().keySet().stream().sorted().toList()) {
      var binding = plan.bindings().get(relationId);
      var rule = binding.rule();
      if (rule.snapshotLabelFieldId() == null || binding.target() == null) {
        continue;
      }
      var source = plan.sources().get(binding.sourceModelId());
      // Values were captured using the caller's historical projection, which may use an older
      // storage code or omit a newly introduced field. Stable identities join both schemas.
      var projection =
          prepared.snapshot().parts().stream()
              .map(RecordSnapshotPlan.Part::model)
              .filter(model -> model.physicalModelId().equals(binding.sourceModelId()))
              .findFirst()
              .orElse(null);
      if (projection == null) {
        continue;
      }
      var field =
          projection.requestedSchema().fields().stream()
              .filter(item -> item.fieldId().equals(rule.source().fieldId()))
              .findFirst()
              .orElse(null);
      if (field == null) {
        continue;
      }
      var reference =
          source.activeSchema().references().stream()
              .filter(item -> item.relationId().equals(relationId))
              .findFirst()
              .orElseThrow(RecordReferenceAdmission::incompatible);
      var target = binding.target();
      var label = RecordReferenceRules.snapshotLabel(target.model(), reference, rule);
      if (!target.access().readableFields().contains(label.code())
          || target.access().maskedFields().contains(label.code())
          || !target.access().readableFields().contains("id")
          || target.access().maskedFields().contains("id")) {
        continue;
      }
      Map<String, List<String>> sourcesByTarget = new LinkedHashMap<>();
      for (var row : rows.getOrDefault(source.physicalModelId(), List.of())) {
        Object value = row.values().get(field.code());
        // Source snapshot projection already applied its field policy. Masked UUIDs are opaque.
        if (value == null || "***".equals(value)) {
          continue;
        }
        String id = RecordReferenceRules.identity(value);
        var check = locked.get(List.of(relationId, source.physicalModelId(), row.id()));
        if (check == null || !Objects.equals(id, check.targetRecordId())) {
          throw RecordReferenceAdmission.incompatible();
        }
        sourcesByTarget.computeIfAbsent(id, ignored -> new ArrayList<>()).add(row.id());
      }
      var ids = new ArrayList<>(sourcesByTarget.keySet());
      for (int offset = 0; offset < ids.size(); offset += 100) {
        var batch = ids.subList(offset, Math.min(ids.size(), offset + 100));
        var capture = target.labels().get(label.fieldId());
        Map<String, RecordReferenceMappingTargets.Captured> protectedRows;
        List<RecordView> selected;
        if (capture != null) {
          protectedRows = capture.capture(java.util.Set.copyOf(batch), reader);
          selected =
              protectedRows.values().stream()
                  .map(RecordReferenceMappingTargets.Captured::value)
                  .toList();
        } else {
          // A target with installed calculation policy requires its prepared original proof.
          // No policy means this is an ordinary label with only its direct read dependency.
          if (target.snapshot().calculation() != null) {
            continue;
          }
          protectedRows = Map.of();
          var query =
              compiler.referenceLabels(target.model(), target.access(), batch, label.code());
          var queried = reader.apply(query);
          if (queried.truncated()) {
            throw RecordReferenceAdmission.incompatible();
          }
          selected = RecordQueryServiceImpl.views(queried, query, target.model(), target.access());
        }
        var seen = new java.util.HashSet<String>();
        for (var row :
            selected.stream().sorted(java.util.Comparator.comparing(RecordView::id)).toList()) {
          if (!batch.contains(row.id()) || !seen.add(row.id())) {
            throw RecordReferenceAdmission.incompatible();
          }
          Object text = row.values().get(label.code());
          if (text != null && !(text instanceof String)) {
            throw RecordReferenceAdmission.incompatible();
          }
          if (text instanceof String value
              && value.length() > RecordReferenceSnapshot.MAX_LABEL_LENGTH) {
            throw limit();
          }
          for (var sourceId : sourcesByTarget.get(row.id())) {
            var captured =
                new RecordReferenceSnapshot(
                    relationId,
                    source.physicalModelId(),
                    sourceId,
                    field.fieldId(),
                    target.model().physicalModelId(),
                    rule.targetAppId(),
                    rule.targetFormId(),
                    rule.targetResourceCode(),
                    row.id(),
                    row.version(),
                    label.fieldId(),
                    (String) text);
            // Bound expanded copies before assembling the complete aggregate JSON. Repeated
            // references share a String in memory but repeat its bytes in the durable snapshot.
            try {
              bytes += JSON.writeValueAsBytes(captured).length + 1L;
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
              throw RecordReferenceAdmission.incompatible();
            }
            if (bytes > RecordSnapshotStore.MAX_BYTES) {
              throw limit();
            }
            var required =
                capture == null
                    ? List.of(
                        new org.simplepoint.plugin.dna.runtime.api.record
                            .RecordReferenceReadRequirement(
                            target.model().physicalModelId(),
                            rule.targetAppId(),
                            rule.targetFormId(),
                            rule.targetResourceCode(),
                            row.id(),
                            null,
                            null,
                            List.of(label.fieldId())))
                    : protectedRows.get(row.id()).requirements().get(label.fieldId());
            var origin =
                new RecordReferenceLabelOrigin(
                    relationId, source.physicalModelId(), sourceId, row.version(), required);
            origin.validate(captured);
            requirementCount += origin.requirements().size();
            if (requirementCount > 16384) {
              throw limit();
            }
            origins.add(origin);
            result.add(captured);
          }
        }
      }
    }
    return new Captured(
        new OwnedRecordView(snapshot.parent(), snapshot.details(), result), List.copyOf(origins));
  }

  record Captured(OwnedRecordView values, List<RecordReferenceLabelOrigin> origins) {}

  private static org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException limit() {
    return new org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException(
        org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Reference labels exceed the immutable snapshot budget");
  }
}
