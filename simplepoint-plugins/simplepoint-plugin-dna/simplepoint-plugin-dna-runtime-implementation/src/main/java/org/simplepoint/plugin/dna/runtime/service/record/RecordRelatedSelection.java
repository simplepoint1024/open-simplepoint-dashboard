package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;

/** Trusted installed source model and current field policy for one fixed inverse selection. */
record RecordRelatedSelection(
    RuntimeRecordModel source,
    RecordAccessContext access,
    RecordReferenceRule rule,
    String targetRecordId,
    String sourceRowId) {
  RecordRelatedSelection(
      RuntimeRecordModel source,
      RecordAccessContext access,
      RecordReferenceRule rule,
      String targetRecordId) {
    this(source, access, rule, targetRecordId, null);
  }

  String sourceCode() {
    if (source.activeSchema().references().stream()
        .noneMatch(
            reference ->
                reference.relationId().equals(rule.relationId())
                    && reference.fieldId().equals(rule.source().fieldId())
                    && reference.targetModelId().equals(rule.targetModelId()))) {
      throw RecordReferenceAdmission.incompatible();
    }
    var field =
        source.requestedSchema().fields().stream()
            .filter(candidate -> candidate.fieldId().equals(rule.source().fieldId()))
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible);
    if (!access.readableFields().contains(field.code())
        || access.maskedFields().contains(field.code())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, "The inverse relation field is unavailable");
    }
    return field.code();
  }
}
