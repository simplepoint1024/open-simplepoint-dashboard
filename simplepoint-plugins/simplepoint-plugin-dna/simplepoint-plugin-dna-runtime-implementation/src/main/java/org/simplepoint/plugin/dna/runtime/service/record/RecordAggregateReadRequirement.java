package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;

/**
 * Relative aggregate input identity captured during calculation, without a resource grant or value.
 * The owning read uses current local field grants. Copying the result to another aggregate converts
 * these coordinates to that target entrance's explicit external read requirements.
 */
record RecordAggregateReadRequirement(
    String modelId, @JsonProperty(required = true) String recordId, List<String> fieldIds) {
  RecordAggregateReadRequirement {
    if (modelId == null || !modelId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Invalid aggregate read model identity");
    }
    if (recordId != null) {
      new RecordReferenceCopyStore.Key("requirement", modelId, recordId);
    }
    if (fieldIds == null
        || fieldIds.isEmpty()
        || fieldIds.size() > 512
        || new HashSet<>(fieldIds).size() != fieldIds.size()
        || fieldIds.stream()
            .anyMatch(field -> field == null || !field.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
      throw new IllegalArgumentException("Invalid aggregate read field identities");
    }
    fieldIds = fieldIds.stream().sorted().toList();
  }

  List<String> rowIdentity() {
    return List.of(modelId, recordId == null ? "" : recordId);
  }
}
