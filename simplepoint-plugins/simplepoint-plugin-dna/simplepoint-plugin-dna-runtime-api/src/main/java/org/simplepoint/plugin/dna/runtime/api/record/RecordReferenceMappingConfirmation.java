package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Explicit confirmation for one relation on one row in the submitted aggregate. */
public record RecordReferenceMappingConfirmation(
    String relationId,
    String detailModelId,
    Integer rowIndex,
    String sourceRowId,
    RecordReferenceMappingObservation observation,
    List<String> applyDefaultMappingIds) {
  /**
   * Parent selection has no detail coordinates. New detail rows use their final request position;
   * persisted rows additionally retain their identity so reordering cannot transfer a confirmation.
   */
  public RecordReferenceMappingConfirmation {
    RecordCalculationDefinition.identity(relationId);
    Objects.requireNonNull(observation, "observation");
    if (detailModelId == null) {
      if (rowIndex != null || sourceRowId != null) {
        throw new IllegalArgumentException("Parent mapping cannot identify a detail row");
      }
    } else {
      detailModelId = RecordQueryLimits.modelId(detailModelId);
      if (rowIndex == null || rowIndex < 0 || rowIndex >= 40) {
        throw new IllegalArgumentException("Mapping detail position is outside its row budget");
      }
      if (sourceRowId != null) {
        sourceRowId = RecordQueryLimits.recordId(sourceRowId);
      }
    }
    applyDefaultMappingIds =
        applyDefaultMappingIds == null ? List.of() : List.copyOf(applyDefaultMappingIds);
    if (applyDefaultMappingIds.size() > 32
        || new HashSet<>(applyDefaultMappingIds).size() != applyDefaultMappingIds.size()) {
      throw new IllegalArgumentException("Default mapping selection is invalid");
    }
    applyDefaultMappingIds.forEach(RecordCalculationDefinition::identity);
    applyDefaultMappingIds = applyDefaultMappingIds.stream().sorted().toList();
  }
}
