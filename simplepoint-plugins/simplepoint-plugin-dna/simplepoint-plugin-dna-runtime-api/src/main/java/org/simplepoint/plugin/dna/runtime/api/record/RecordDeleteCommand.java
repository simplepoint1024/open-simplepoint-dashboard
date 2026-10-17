package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Optimistically soft-deletes one active record. */
public record RecordDeleteCommand(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    String recordId,
    long expectedVersion,
    String idempotencyKey,
    long writePolicyVersion
) {

  /** Compatibility constructor for callers that do not request durable replay. */
  public RecordDeleteCommand(String physicalModelId, RecordSchemaReference schemaReference,
      String recordId, long expectedVersion) {
    this(physicalModelId, schemaReference, recordId, expectedVersion, null);
  }

  /** Existing keyed requests use the initial admission version. */
  public RecordDeleteCommand(String physicalModelId, RecordSchemaReference schemaReference,
      String recordId, long expectedVersion, String idempotencyKey) {
    this(physicalModelId, schemaReference, recordId, expectedVersion, idempotencyKey, 0);
  }

  /** Validates identity and expected version. */
  public RecordDeleteCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordQueryLimits.writePolicyVersion(writePolicyVersion);
    idempotencyKey = idempotencyKey == null ? null : RecordQueryLimits.idempotencyKey(idempotencyKey);
    recordId = RecordQueryLimits.recordId(recordId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
  }

  /** Binds legacy requests to version zero without relaxing strict primitive JSON validation. */
  @JsonCreator
  public static RecordDeleteCommand fromJson(
      @JsonProperty("physicalModelId") final String physicalModelId,
      @JsonProperty("schemaReference") final RecordSchemaReference schemaReference,
      @JsonProperty("recordId") final String recordId,
      @JsonProperty("expectedVersion") final long expectedVersion,
      @JsonProperty("idempotencyKey") final String idempotencyKey,
      @JsonProperty("writePolicyVersion") final Long writePolicyVersion
  ) {
    return new RecordDeleteCommand(physicalModelId, schemaReference, recordId, expectedVersion, idempotencyKey,
        writePolicyVersion == null ? 0 : writePolicyVersion);
  }
}
