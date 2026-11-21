package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;
import java.util.UUID;

/**
 * A fixed inverse selection over an installed relation, intersected with an ordinary page query.
 */
public record RelatedRecordPageQuery(
    RecordPageQuery records, String relationId, String targetModelId, String targetRecordId) {
  /** Only stable relation and record identities are accepted; no caller-supplied join or field. */
  public RelatedRecordPageQuery {
    Objects.requireNonNull(records, "records");
    RecordCalculationDefinition.identity(relationId);
    targetModelId = RecordQueryLimits.modelId(targetModelId);
    Objects.requireNonNull(targetRecordId, "targetRecordId");
    String canonical = UUID.fromString(targetRecordId).toString();
    if (!canonical.equalsIgnoreCase(targetRecordId)) {
      throw new IllegalArgumentException("A canonical target record identity is required");
    }
    targetRecordId = canonical;
  }
}
