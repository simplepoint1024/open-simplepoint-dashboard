package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded ordered mutation group committed atomically for one physical model. */
public record RecordTransactionCommand(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    List<RecordMutation> mutations,
    int timeoutMs,
    String idempotencyKey,
    long writePolicyVersion
) {

  /** Legacy unkeyed transactions retain their original optimistic-conflict semantics. */
  public RecordTransactionCommand(String physicalModelId, RecordSchemaReference schemaReference,
      List<RecordMutation> mutations, int timeoutMs) {
    this(physicalModelId, schemaReference, mutations, timeoutMs, null);
  }

  /** Existing keyed transactions use the initial admission version. */
  public RecordTransactionCommand(String physicalModelId, RecordSchemaReference schemaReference,
      List<RecordMutation> mutations, int timeoutMs, String idempotencyKey) {
    this(physicalModelId, schemaReference, mutations, timeoutMs, idempotencyKey, 0);
  }

  /** Validates transaction size, references, and timeout. */
  public RecordTransactionCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordQueryLimits.writePolicyVersion(writePolicyVersion);
    idempotencyKey = idempotencyKey == null ? null : RecordQueryLimits.idempotencyKey(idempotencyKey);
    mutations = mutations == null ? List.of() : List.copyOf(mutations);
    if (mutations.isEmpty() || mutations.size() > 100) {
      throw new IllegalArgumentException("Record transaction requires 1 to 100 mutations");
    }
    if (new HashSet<>(mutations.stream().map(RecordMutation::reference).toList()).size()
        != mutations.size()) {
      throw new IllegalArgumentException("Mutation references must be unique");
    }
    if (timeoutMs < 1 || timeoutMs > 120_000) {
      throw new IllegalArgumentException("Record transaction timeout is invalid");
    }
  }

  /** Binds legacy requests to version zero without relaxing strict primitive JSON validation. */
  @JsonCreator
  public static RecordTransactionCommand fromJson(
      @JsonProperty("physicalModelId") final String physicalModelId,
      @JsonProperty("schemaReference") final RecordSchemaReference schemaReference,
      @JsonProperty("mutations") final List<RecordMutation> mutations,
      @JsonProperty("timeoutMs") final int timeoutMs,
      @JsonProperty("idempotencyKey") final String idempotencyKey,
      @JsonProperty("writePolicyVersion") final Long writePolicyVersion
  ) {
    return new RecordTransactionCommand(physicalModelId, schemaReference, mutations, timeoutMs, idempotencyKey,
        writePolicyVersion == null ? 0 : writePolicyVersion);
  }
}
