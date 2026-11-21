package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;

/** One selection contract for observation, preload and confirmed aggregate preparation. */
final class RecordReferenceMappingFields {
  private RecordReferenceMappingFields() {}

  static Set<String> select(RecordReferenceRule rule, Set<String> selectedDefaults) {
    var defaults =
        rule.mappings().stream()
            .filter(mapping -> mapping.mode() == RecordReferenceMapping.Mode.EDITABLE_DEFAULT)
            .map(RecordReferenceMapping::mappingId)
            .collect(java.util.stream.Collectors.toSet());
    if (!defaults.containsAll(selectedDefaults)) {
      throw RecordReferenceAdmission.incompatible();
    }
    return rule.mappings().stream()
        .filter(
            mapping ->
                mapping.mode() == RecordReferenceMapping.Mode.SERVER_SNAPSHOT
                    || selectedDefaults.contains(mapping.mappingId()))
        .map(RecordReferenceMapping::targetFieldId)
        .collect(java.util.stream.Collectors.toSet());
  }
}
