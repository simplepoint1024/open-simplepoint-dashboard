package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;

/** Required defaults are deferred only for this exact confirmed row and installed mapping. */
final class RecordReferenceMappingIntent {
  private RecordReferenceMappingIntent() {}

  static Set<String> defaults(
      RecordCalculationWriteSupport.Binding binding,
      MappedOwnedRecordCommand command,
      String modelId,
      Integer rowIndex) {
    if (command == null) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (var confirmation : command.confirmations()) {
      String sourceId =
          confirmation.detailModelId() == null
              ? command.command().parent().physicalModelId()
              : confirmation.detailModelId();
      if (!sourceId.equals(modelId) || !Objects.equals(rowIndex, confirmation.rowIndex())) {
        continue;
      }
      if (binding == null) {
        throw RecordReferenceAdmission.incompatible();
      }
      var definition = binding.installed().policy().definition();
      var rule =
          definition.referencesOrEmpty().stream()
              .filter(candidate -> candidate.relationId().equals(confirmation.relationId()))
              .findFirst()
              .orElseThrow(RecordReferenceAdmission::incompatible);
      var owner =
          rule.source().scope().equals("parent")
              ? binding.parent().physicalModelId()
              : definition.details().get(rule.source().scope()).physicalModelId();
      if (!owner.equals(modelId) || rule.mappings().isEmpty()) {
        throw RecordReferenceAdmission.incompatible();
      }
      for (var selected : confirmation.applyDefaultMappingIds()) {
        var mapping =
            rule.mappings().stream()
                .filter(
                    value ->
                        value.mappingId().equals(selected)
                            && value.mode() == RecordReferenceMapping.Mode.EDITABLE_DEFAULT)
                .findFirst()
                .orElseThrow(RecordReferenceAdmission::incompatible);
        result.add(mapping.destinationFieldId());
      }
    }
    return Set.copyOf(result);
  }
}
