package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

/** Attempt-local value and provenance effects for one confirmed relation on one source row. */
final class RecordReferenceCopyEffects {
  private RecordReferenceCopyEffects() {}

  static Applied apply(
      List<RecordReferenceCopyOrigin> previous,
      RecordReferenceRule rule,
      RecordReferenceCopyPlan.Prepared prepared,
      Map<String, List<RecordReferenceReadRequirement>> requirementsByTargetField) {
    // Validate the existing protected ledger before carrying any provenance forward.
    var checked = new RecordReferenceCopyStore.Stored(0, previous);
    Map<String, RecordReferenceCopyOrigin> origins = new LinkedHashMap<>();
    checked.origins().forEach(origin -> origins.put(origin.destinationFieldId(), origin));
    Map<String, RecordReferenceMapping> mappings = new LinkedHashMap<>();
    rule.mappings().forEach(mapping -> mappings.put(mapping.destinationFieldId(), mapping));
    Map<String, LogicalValue> values = new LinkedHashMap<>();
    append(
        rule,
        prepared,
        mappings,
        prepared.managed(),
        RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
        values,
        origins,
        requirementsByTargetField);
    append(
        rule,
        prepared,
        mappings,
        prepared.suggestions(),
        RecordReferenceMapping.Mode.EDITABLE_DEFAULT,
        values,
        origins,
        requirementsByTargetField);
    // Unselected editable defaults keep both their edited value and their original source. A
    // changed foreign key or publication never relabels old copied data as newly authorized data.
    return new Applied(
        values, new RecordReferenceCopyStore.Stored(0, List.copyOf(origins.values())).origins());
  }

  private static void append(
      RecordReferenceRule rule,
      RecordReferenceCopyPlan.Prepared prepared,
      Map<String, RecordReferenceMapping> mappings,
      Map<String, LogicalValue> outputs,
      RecordReferenceMapping.Mode mode,
      Map<String, LogicalValue> values,
      Map<String, RecordReferenceCopyOrigin> origins,
      Map<String, List<RecordReferenceReadRequirement>> requirementsByTargetField) {
    for (var entry : outputs.entrySet()) {
      var mapping = mappings.get(entry.getKey());
      if (mapping == null
          || mapping.mode() != mode
          || entry.getValue() == null
          || values.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
            "Prepared reference outputs do not match their installed mapping policy");
      }
      var observation = prepared.observed();
      if (observation.targetRecordId() == null) {
        if (!LogicalValue.nullValue().equals(entry.getValue())) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
              "An empty reference cannot supply a copied value");
        }
        origins.remove(entry.getKey());
      } else {
        var requirements = requirementsByTargetField.get(mapping.targetFieldId());
        if (requirements == null || requirements.isEmpty()) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
              "Prepared reference value has no frozen permission dependencies");
        }
        origins.put(
            entry.getKey(),
            new RecordReferenceCopyOrigin(
                rule.relationId(),
                mapping.mappingId(),
                mode,
                mapping.destinationFieldId(),
                observation.revision(),
                rule.targetModelId(),
                rule.targetAppId(),
                rule.targetFormId(),
                rule.targetResourceCode(),
                observation.targetRecordId(),
                observation.targetVersion(),
                mapping.targetFieldId(),
                requirements));
      }
    }
  }

  /** Business fields and their protected origins must be committed by the same aggregate owner. */
  record Applied(Map<String, LogicalValue> values, List<RecordReferenceCopyOrigin> origins) {
    Applied {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
      origins = List.copyOf(origins);
    }
  }
}
