package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

/** Immutable inverse-operation context; part of the durable command fingerprint. */
public record RelatedRecordScope(
    String contractVersion,
    String relationId,
    String targetModelId,
    String targetRecordId,
    Mode mode,
    @JsonInclude(JsonInclude.Include.NON_NULL) String sourceRowId) {
  /** Maintaining a record and clearing one nullable reference are distinct intents. */
  public enum Mode {
    MAINTAIN,
    UNLINK
  }

  /** Accepts stable identities only; the server resolves the source field and target entrance. */
  public RelatedRecordScope {
    if (!"1".equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported related record scope version");
    }
    RecordCalculationDefinition.identity(relationId);
    targetModelId = RecordQueryLimits.modelId(targetModelId);
    targetRecordId = canonical(targetRecordId);
    Objects.requireNonNull(mode, "mode");
    if (sourceRowId != null) {
      sourceRowId = canonical(sourceRowId);
      if (mode != Mode.UNLINK) {
        throw new IllegalArgumentException("Only unlink intent selects an individual owned row");
      }
    }
  }

  private static String canonical(String value) {
    Objects.requireNonNull(value, "record identity");
    String id = UUID.fromString(value).toString();
    if (!id.equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("A canonical saved record identity is required");
    }
    return id;
  }
}
