package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/** Optimistically updates one active record. */
public record RecordUpdateCommand(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    String recordId,
    long expectedVersion,
    Map<String, Object> values,
    String idempotencyKey,
    long writePolicyVersion
) {

  /** Compatibility constructor for callers that do not request durable replay. */
  public RecordUpdateCommand(String physicalModelId, RecordSchemaReference schemaReference,
      String recordId, long expectedVersion, Map<String, Object> values) {
    this(physicalModelId, schemaReference, recordId, expectedVersion, values, null);
  }

  /** Existing keyed requests use the initial admission version. */
  public RecordUpdateCommand(String physicalModelId, RecordSchemaReference schemaReference,
      String recordId, long expectedVersion, Map<String, Object> values, String idempotencyKey) {
    this(physicalModelId, schemaReference, recordId, expectedVersion, values, idempotencyKey, 0);
  }

  /** Validates identity, expected version, and field keys. */
  public RecordUpdateCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordQueryLimits.writePolicyVersion(writePolicyVersion);
    idempotencyKey = idempotencyKey == null ? null : RecordQueryLimits.idempotencyKey(idempotencyKey);
    recordId = RecordQueryLimits.recordId(recordId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
    values = RecordValues.copy(values);
    if (values.isEmpty()) {
      throw new IllegalArgumentException("Record update values are required");
    }
  }

  /** Binds legacy requests to version zero without relaxing strict primitive JSON validation. */
  @JsonCreator
  public static RecordUpdateCommand fromJson(
      @JsonProperty("physicalModelId") final String physicalModelId,
      @JsonProperty("schemaReference") final RecordSchemaReference schemaReference,
      @JsonProperty("recordId") final String recordId,
      @JsonProperty("expectedVersion") final long expectedVersion,
      @JsonProperty("values") final Map<String, Object> values,
      @JsonProperty("idempotencyKey") final String idempotencyKey,
      @JsonProperty("writePolicyVersion") final Long writePolicyVersion
  ) {
    return new RecordUpdateCommand(physicalModelId, schemaReference, recordId, expectedVersion, values, idempotencyKey,
        writePolicyVersion == null ? 0 : writePolicyVersion);
  }
}
