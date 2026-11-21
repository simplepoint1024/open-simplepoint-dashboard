package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;

/** Observes one installed mapping without accepting copied values or permission assertions. */
public record RecordReferenceMappingQuery(
    String contractVersion,
    String physicalModelId,
    RecordSchemaReference schemaReference,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) long writePolicyVersion,
    String relationId,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) String targetRecordId,
    List<String> applyDefaultMappingIds,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) int timeoutMs) {
  /** The null target is an explicit unlink observation, not a missing selection. */
  public RecordReferenceMappingQuery {
    if (!"1".equals(contractVersion)
        || writePolicyVersion < 1
        || timeoutMs < 1
        || timeoutMs > 30_000) {
      throw new IllegalArgumentException("Invalid reference mapping query");
    }
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordCalculationDefinition.identity(relationId);
    if (targetRecordId != null
        && !java.util.UUID.fromString(targetRecordId).toString().equals(targetRecordId)) {
      throw new IllegalArgumentException("A canonical target identity is required");
    }
    applyDefaultMappingIds =
        applyDefaultMappingIds == null ? List.of() : List.copyOf(applyDefaultMappingIds);
    if (applyDefaultMappingIds.size() > 32
        || applyDefaultMappingIds.stream().distinct().count() != applyDefaultMappingIds.size()) {
      throw new IllegalArgumentException("Invalid default mapping selection");
    }
    applyDefaultMappingIds.forEach(RecordCalculationDefinition::identity);
    applyDefaultMappingIds = applyDefaultMappingIds.stream().sorted().toList();
  }
}
