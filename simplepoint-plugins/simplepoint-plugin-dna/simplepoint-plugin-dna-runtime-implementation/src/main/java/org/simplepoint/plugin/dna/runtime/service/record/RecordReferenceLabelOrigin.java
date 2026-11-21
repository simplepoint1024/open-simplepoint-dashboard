package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;

/** Internal immutable proof of one frozen label, without duplicating its display value. */
record RecordReferenceLabelOrigin(
    String relationId,
    String sourceModelId,
    String sourceRecordId,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) long targetVersion,
    List<RecordReferenceReadRequirement> requirements) {
  RecordReferenceLabelOrigin {
    if (relationId == null
        || sourceModelId == null
        || sourceRecordId == null
        || targetVersion < 0
        || requirements == null
        || requirements.isEmpty()
        || requirements.size() > 256) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    requirements =
        requirements.stream()
            .sorted(
                java.util.Comparator.comparing(
                    requirement -> String.join("\0", requirement.rowIdentity())))
            .toList();
    if (requirements.stream().map(RecordReferenceReadRequirement::rowIdentity).distinct().count()
        != requirements.size()) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
  }

  List<String> key() {
    return List.of(relationId, sourceModelId, sourceRecordId);
  }

  static List<String> key(RecordReferenceSnapshot label) {
    return List.of(label.relationId(), label.sourceModelId(), label.sourceRecordId());
  }

  void validate(RecordReferenceSnapshot label) {
    if (!key().equals(key(label))
        || targetVersion != label.targetVersion()
        || requirements.stream()
            .noneMatch(
                required ->
                    required.modelId().equals(label.targetModelId())
                        && required.appId().equals(label.targetAppId())
                        && required.formId().equals(label.targetFormId())
                        && required.resourceCode().equals(label.targetResourceCode())
                        && required.recordId().equals(label.targetRecordId())
                        && required.detailModelId() == null
                        && required.fieldIds().contains(label.labelFieldId()))) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
  }
}
