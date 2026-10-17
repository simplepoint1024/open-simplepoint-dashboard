package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/** Creates one record with server-owned identity, tenant, version, and audit fields. */
public record RecordCreateCommand(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    String idempotencyKey,
    Map<String, Object> values,
    long writePolicyVersion
) {

  /** Existing callers select the explicit initial write-policy version. */
  public RecordCreateCommand(String physicalModelId, RecordSchemaReference schemaReference,
      String idempotencyKey, Map<String, Object> values) {
    this(physicalModelId, schemaReference, idempotencyKey, values, 0);
  }

  /** Validates the model identity and dynamic field keys. */
  public RecordCreateCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    idempotencyKey = RecordQueryLimits.idempotencyKey(idempotencyKey);
    values = RecordValues.copy(values);
    RecordQueryLimits.writePolicyVersion(writePolicyVersion);
  }

  /** Binds legacy requests to version zero without relaxing strict primitive JSON validation. */
  @JsonCreator
  public static RecordCreateCommand fromJson(
      @JsonProperty("physicalModelId") final String physicalModelId,
      @JsonProperty("schemaReference") final RecordSchemaReference schemaReference,
      @JsonProperty("idempotencyKey") final String idempotencyKey,
      @JsonProperty("values") final Map<String, Object> values,
      @JsonProperty("writePolicyVersion") final Long writePolicyVersion
  ) {
    return new RecordCreateCommand(physicalModelId, schemaReference, idempotencyKey, values,
        writePolicyVersion == null ? 0 : writePolicyVersion);
  }
}
