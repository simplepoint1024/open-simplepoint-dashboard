package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/**
 * Validates frozen provenance before any target read and intersects the visible source projection.
 */
final class RecordReferenceSnapshotProvenance {
  private RecordReferenceSnapshotProvenance() {}

  static List<RecordReferenceSnapshot> visible(
      RuntimeRecordModel parent,
      List<RuntimeRecordModel> children,
      OwnedRecordView visible,
      List<RecordReferenceSnapshot> captured,
      RecordCalculationStore.Installed historical) {
    if (captured.isEmpty()) {
      return List.of();
    }
    if (historical == null
        || !parent.appId().equals(historical.policy().appId())
        || !parent.formId().equals(historical.policy().formId())) {
      throw invalid();
    }
    var definition = historical.policy().definition();
    Map<String, RecordReferenceRule> rules = new LinkedHashMap<>();
    definition.referencesOrEmpty().forEach(rule -> rules.put(rule.relationId(), rule));
    Map<String, RuntimeRecordModel> models = new LinkedHashMap<>();
    models.put(parent.physicalModelId(), parent);
    children.forEach(model -> models.put(model.physicalModelId(), model));
    Map<String, Map<String, RecordView>> rows = new LinkedHashMap<>();
    rows.put(parent.physicalModelId(), Map.of(visible.parent().id(), visible.parent()));
    visible
        .details()
        .forEach(
            (model, values) -> {
              Map<String, RecordView> indexed = new LinkedHashMap<>();
              for (var row : values) {
                if (indexed.putIfAbsent(row.id(), row) != null) {
                  throw invalid();
                }
              }
              rows.put(model, indexed);
            });
    var result = new ArrayList<RecordReferenceSnapshot>();
    for (var label : captured) {
      var rule = rules.get(label.relationId());
      if (rule == null) {
        throw invalid();
      }
      var detail = definition.details().get(rule.source().scope());
      String source =
          rule.source().scope().equals("parent")
              ? parent.physicalModelId()
              : detail == null ? null : detail.physicalModelId();
      if (!Objects.equals(source, label.sourceModelId())
          || !rule.source().fieldId().equals(label.sourceFieldId())
          || !rule.targetModelId().equals(label.targetModelId())
          || !rule.targetAppId().equals(label.targetAppId())
          || !rule.targetFormId().equals(label.targetFormId())
          || !rule.targetResourceCode().equals(label.targetResourceCode())
          || !Objects.equals(rule.snapshotLabelFieldId(), label.labelFieldId())) {
        throw invalid();
      }
      var model = models.get(source);
      var row = rows.getOrDefault(source, Map.of()).get(label.sourceRecordId());
      if (model == null || row == null) {
        // A caller may request fewer owned collections than the original immutable snapshot.
        continue;
      }
      var field =
          model.requestedSchema().fields().stream()
              .filter(value -> value.fieldId().equals(label.sourceFieldId()))
              .findFirst()
              .orElse(null);
      if (field == null) {
        continue;
      }
      Object value = row.values().get(field.code());
      if (value == null || "***".equals(value)) {
        continue;
      }
      if (!(value instanceof String id) || !id.equalsIgnoreCase(label.targetRecordId())) {
        throw invalid();
      }
      result.add(label);
    }
    return List.copyOf(result);
  }

  static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Reference snapshot provenance is absent or inconsistent");
  }
}
