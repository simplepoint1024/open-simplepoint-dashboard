package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;

/** Bounded identity-only read requiring current access to one stable reference label field. */
public record RecordReferenceVisibilityQuery(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    String labelFieldId,
    List<String> recordIds,
    int timeoutMs) {
  /** Validates stable field identity, canonical record IDs and the shared remaining time budget. */
  public RecordReferenceVisibilityQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordCalculationDefinition.identity(labelFieldId);
    if (recordIds == null || recordIds.isEmpty() || recordIds.size() > 100) {
      throw new IllegalArgumentException("Reference visibility requires 1 to 100 identities");
    }
    recordIds =
        recordIds.stream().map(RecordReferenceVisibilityQuery::recordId).distinct().toList();
    if (timeoutMs < 1 || timeoutMs > 30_000) {
      throw new IllegalArgumentException("Invalid reference visibility timeout");
    }
  }

  private static String recordId(String value) {
    if (value == null
        || value.length() != 36
        || !java.util.UUID.fromString(value).toString().equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("Invalid reference record identity");
    }
    return java.util.UUID.fromString(value).toString();
  }
}
