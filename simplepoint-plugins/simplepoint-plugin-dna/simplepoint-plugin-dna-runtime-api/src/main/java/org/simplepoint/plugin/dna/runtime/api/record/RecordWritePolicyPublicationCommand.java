package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Trusted owner publication intent; all checks and the version transition share one target transaction. */
public record RecordWritePolicyPublicationCommand(
    String physicalModelId,
    String appId,
    String formId,
    RecordSchemaReference schemaReference,
    long expectedVersion,
    long targetVersion,
    String publicationKey,
    String policyChecksum,
    Map<String, RecordCompletenessQuery> checks,
    int timeoutMs,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, RecordViolationQuery> valueChecks
) {

  /** Bounds the entire inspection, requires a monotonic transition and rejects checks for other owners. */
  public RecordWritePolicyPublicationCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    appId = RecordQueryLimits.modelId(appId);
    formId = RecordQueryLimits.modelId(formId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordQueryLimits.writePolicyVersion(expectedVersion);
    if (targetVersion <= expectedVersion || timeoutMs < 1 || timeoutMs > 120_000) {
      throw new IllegalArgumentException("Invalid policy transition or execution budget");
    }
    publicationKey = RecordQueryLimits.idempotencyKey(publicationKey);
    if (policyChecksum == null || !policyChecksum.matches("[a-fA-F0-9]{64}")) {
      throw new IllegalArgumentException("A policy checksum is required");
    }
    policyChecksum = policyChecksum.toLowerCase(Locale.ROOT);
    checks = Map.copyOf(Objects.requireNonNull(checks, "checks"));
    valueChecks = valueChecks == null ? Map.of() : Map.copyOf(valueChecks);
    validateBudget(checks, valueChecks);
    for (var entry : checks.entrySet()) {
      RecordQueryLimits.idempotencyKey(entry.getKey());
      RecordCompletenessQuery query = entry.getValue();
      if (!physicalModelId.equals(query.physicalModelId()) || !appId.equals(query.appId()) || !formId.equals(query.formId())) {
        throw new IllegalArgumentException("Every check must belong to the publication model and owner");
      }
    }
    for (var entry : valueChecks.entrySet()) {
      RecordQueryLimits.idempotencyKey(entry.getKey());
      RecordViolationQuery query = entry.getValue();
      if (!physicalModelId.equals(query.physicalModelId()) || !appId.equals(query.appId()) || !formId.equals(query.formId())) {
        throw new IllegalArgumentException("Every value check must belong to the publication model and owner");
      }
    }
  }

  /** Missing value checks retain the original wire shape and durable publication fingerprint. */
  public RecordWritePolicyPublicationCommand(final String physicalModelId, final String appId, final String formId,
      final RecordSchemaReference schemaReference, final long expectedVersion, final long targetVersion,
      final String publicationKey, final String policyChecksum, final Map<String, RecordCompletenessQuery> checks, final int timeoutMs) {
    this(physicalModelId, appId, formId, schemaReference, expectedVersion, targetVersion, publicationKey,
        policyChecksum, checks, timeoutMs, Map.of());
  }

  /** Lets trusted compilers reject oversized plans before persisting an immutable publication intent. */
  public static void validateBudget(final Map<String, RecordCompletenessQuery> checks) {
    validateBudget(checks, Map.of());
  }

  /** Completeness and value checks share one budget and one namespace for report keys. */
  public static void validateBudget(final Map<String, RecordCompletenessQuery> checks, final Map<String, RecordViolationQuery> valueChecks) {
    if (checks.size() + valueChecks.size() > 100 || checks.values().stream().mapToInt(query -> query.fields().size()).sum() > 1000
        || checks.values().stream().mapToInt(query -> query.condition() == null ? 0 : query.condition().complexity()).sum()
            + valueChecks.values().stream().mapToInt(query -> query.condition().complexity()).sum() > 16_384
        || checks.keySet().stream().anyMatch(valueChecks::containsKey)) {
      throw new IllegalArgumentException("Policy inspection exceeds the total check budget");
    }
  }

}
