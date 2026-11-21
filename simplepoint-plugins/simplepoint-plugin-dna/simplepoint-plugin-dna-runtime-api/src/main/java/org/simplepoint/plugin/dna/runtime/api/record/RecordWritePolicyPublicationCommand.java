package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted owner publication intent; all checks and the version transition share one target
 * transaction.
 */
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
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, RecordViolationQuery> valueChecks,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordCalculationDefinition calculation,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RecordCollectionRule> retiringCollections,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        RecordReferenceCollectionMigration referenceConversion,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordCollectionRebindMigration collectionRebind) {

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
    if (calculation != null) {
      for (var detail : calculation.details().values()) {
        if (physicalModelId.equals(detail.physicalModelId())) {
          throw new IllegalArgumentException(
              "A calculation detail cannot reference its parent model");
        }
      }
    }
    retiringCollections = retiringCollections == null
        ? List.of() : RecordCollectionRule.canonical(retiringCollections, List.of());
    if (!retiringCollections.isEmpty()) {
      if (calculation == null) {
        throw new IllegalArgumentException("Retirement requires an explicit replacement policy");
      }
      var activeIds = java.util.stream.Stream.concat(
              calculation.collectionsOrEmpty().stream().map(RecordCollectionRule::relationId),
              calculation.referencesOrEmpty().stream().map(RecordReferenceRule::relationId))
          .collect(java.util.stream.Collectors.toSet());
      if (retiringCollections.stream().anyMatch(rule -> activeIds.contains(rule.relationId()))) {
        throw new IllegalArgumentException(
            "A retired collection cannot remain in the replacement policy");
      }
    }
    if (referenceConversion != null
        && (calculation == null
            || calculation.referencesOrEmpty().stream()
                .noneMatch(
                    rule ->
                        rule.retired()
                            && rule.relationId().equals(referenceConversion.sourceRelationId()))
            || calculation.collectionsOrEmpty().stream()
                .noneMatch(
                    rule ->
                        rule.relationId().equals(referenceConversion.destinationRelationId())))) {
      throw new IllegalArgumentException(
          "Conversion requires its retired source and a destination collection");
    }
    if (collectionRebind != null && (referenceConversion != null || calculation == null
        || retiringCollections.stream().noneMatch(rule -> rule.relationId().equals(collectionRebind.sourceRelationId()))
        || calculation.collectionsOrEmpty().stream().noneMatch(rule -> rule.relationId().equals(collectionRebind.destinationRelationId())))) {
      throw new IllegalArgumentException("Rebind requires a retired source and a new destination collection");
    }
    validateBudget(checks, valueChecks);
    for (var entry : checks.entrySet()) {
      RecordQueryLimits.idempotencyKey(entry.getKey());
      RecordCompletenessQuery query = entry.getValue();
      if (!physicalModelId.equals(query.physicalModelId()) || !appId.equals(query.appId()) || !formId.equals(query.formId())) {
        throw new IllegalArgumentException(
            "Every check must belong to the publication model and owner");
      }
    }
    for (var entry : valueChecks.entrySet()) {
      RecordQueryLimits.idempotencyKey(entry.getKey());
      RecordViolationQuery query = entry.getValue();
      if (!physicalModelId.equals(query.physicalModelId()) || !appId.equals(query.appId()) || !formId.equals(query.formId())) {
        throw new IllegalArgumentException(
            "Every value check must belong to the publication model and owner");
      }
    }
  }

  /** Reference conversion and ordinary publication preserve their original wire contracts. */
  public RecordWritePolicyPublicationCommand(
      String physicalModelId, String appId, String formId, RecordSchemaReference schemaReference,
      long expectedVersion, long targetVersion, String publicationKey, String policyChecksum,
      Map<String, RecordCompletenessQuery> checks, int timeoutMs, Map<String, RecordViolationQuery> valueChecks,
      RecordCalculationDefinition calculation, List<RecordCollectionRule> retiringCollections,
      RecordReferenceCollectionMigration referenceConversion) {
    this(physicalModelId, appId, formId, schemaReference, expectedVersion, targetVersion, publicationKey,
        policyChecksum, checks, timeoutMs, valueChecks, calculation, retiringCollections, referenceConversion, null);
  }

  /** Existing collection retirement intents retain their original JSON and fingerprints. */
  public RecordWritePolicyPublicationCommand(
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
      Map<String, RecordViolationQuery> valueChecks,
      RecordCalculationDefinition calculation,
      List<RecordCollectionRule> retiringCollections) {
    this(
        physicalModelId,
        appId,
        formId,
        schemaReference,
        expectedVersion,
        targetVersion,
        publicationKey,
        policyChecksum,
        checks,
        timeoutMs,
        valueChecks,
        calculation,
        retiringCollections,
        null);
  }

  /** Existing publication intents retain their original JSON and fingerprints. */
  public RecordWritePolicyPublicationCommand(
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
      Map<String, RecordViolationQuery> valueChecks,
      RecordCalculationDefinition calculation) {
    this(physicalModelId, appId, formId, schemaReference, expectedVersion, targetVersion, publicationKey,
        policyChecksum, checks, timeoutMs, valueChecks, calculation, List.of());
  }

  /** Missing value checks retain the original wire shape and durable publication fingerprint. */
  public RecordWritePolicyPublicationCommand(final String physicalModelId, final String appId, final String formId,
      final RecordSchemaReference schemaReference, final long expectedVersion, final long targetVersion,
      final String publicationKey, final String policyChecksum, final Map<String, RecordCompletenessQuery> checks, final int timeoutMs) {
    this(physicalModelId, appId, formId, schemaReference, expectedVersion, targetVersion, publicationKey,
        policyChecksum, checks, timeoutMs, Map.of(), null);
  }

  /** Missing calculations retain every existing publication, cancellation and retry identity. */
  public RecordWritePolicyPublicationCommand(final String physicalModelId, final String appId, final String formId,
      final RecordSchemaReference schemaReference, final long expectedVersion, final long targetVersion,
      final String publicationKey, final String policyChecksum, final Map<String, RecordCompletenessQuery> checks,
      final int timeoutMs, final Map<String, RecordViolationQuery> valueChecks) {
    this(physicalModelId, appId, formId, schemaReference, expectedVersion, targetVersion, publicationKey,
        policyChecksum, checks, timeoutMs, valueChecks, null);
  }

  /** Lets trusted compilers reject oversized plans before persisting an immutable publication intent. */
  public static void validateBudget(final Map<String, RecordCompletenessQuery> checks) {
    validateBudget(checks, Map.of());
  }

  /** The command constructor checks that both conversion endpoints are present in the policy. */
  public RecordReferenceRule conversionSource() {
    return referenceConversion == null
        ? null
        : calculation.referencesOrEmpty().stream()
            .filter(rule -> rule.relationId().equals(referenceConversion.sourceRelationId()))
            .findFirst()
            .orElseThrow();
  }

  /** The independently stored candidate collection exposed after the conversion commits. */
  public RecordCollectionRule conversionDestination() {
    return referenceConversion == null
        ? null
        : calculation.collectionsOrEmpty().stream()
            .filter(rule -> rule.relationId().equals(referenceConversion.destinationRelationId()))
            .findFirst()
            .orElseThrow();
  }

  public RecordCollectionRule rebindSource() {
    return collectionRebind == null ? null : retiringCollections.stream()
        .filter(rule -> rule.relationId().equals(collectionRebind.sourceRelationId()))
        .findFirst().orElseThrow();
  }

  public RecordCollectionRule rebindDestination() {
    return collectionRebind == null ? null : calculation.collectionsOrEmpty().stream()
        .filter(rule -> rule.relationId().equals(collectionRebind.destinationRelationId()))
        .findFirst().orElseThrow();
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
