package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;

/**
 * One root's bounded incremental collection change. The installed owner definition resolves the
 * edge store and target model; callers cannot select physical tables or supply target permissions.
 * Both root versions fence all incident collection changes as well as ordinary aggregate edits.
 */
public record CollectionRecordCommand(
    String contractVersion,
    String sourceModelId,
    RecordSchemaReference sourceSchema,
    String relationId,
    String sourceRecordId,
    Long expectedSourceVersion,
    List<CollectionEdgeMutation> mutations,
    String idempotencyKey,
    int timeoutMs) {
  /** Every member is an observed intent, never a replacement of a paginated set of identifiers. */
  public CollectionRecordCommand {
    if (!"1".equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported collection command version");
    }
    sourceModelId = RecordQueryLimits.modelId(sourceModelId);
    Objects.requireNonNull(sourceSchema, "sourceSchema");
    RecordCalculationDefinition.identity(relationId);
    sourceRecordId = CollectionEdgeMutation.canonicalRecordId(sourceRecordId);
    CollectionEdgeMutation.version(expectedSourceVersion, false);
    mutations = mutations == null ? List.of() : List.copyOf(mutations);
    if (mutations.isEmpty()
        || mutations.size() > 100
        || mutations.stream().map(CollectionEdgeMutation::targetRecordId).distinct().count()
            != mutations.size()) {
      throw new IllegalArgumentException("A collection command requires 1 to 100 distinct targets");
    }
    // Request order has no semantic effect. Canonicalization also fixes operation fingerprints.
    mutations =
        mutations.stream()
            .sorted(java.util.Comparator.comparing(CollectionEdgeMutation::targetRecordId))
            .toList();
    idempotencyKey = RecordQueryLimits.idempotencyKey(idempotencyKey);
    if (timeoutMs < 1 || timeoutMs > 120_000) {
      throw new IllegalArgumentException("Collection command timeout is invalid");
    }
  }
}
