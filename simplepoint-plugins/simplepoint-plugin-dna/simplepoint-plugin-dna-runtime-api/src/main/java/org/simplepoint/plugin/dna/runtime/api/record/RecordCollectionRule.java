package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/** Immutable owning-root collection governance; never supplied by an ordinary record command. */
public record RecordCollectionRule(
    String contractVersion,
    String relationId,
    String edgeModelId,
    RecordSchemaReference edgeSchema,
    String targetModelId,
    String targetAppId,
    String targetFormId,
    String targetResourceCode,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordValidationCondition eligibility,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordValidationCondition mutableWhen,
    @JsonInclude(JsonInclude.Include.NON_NULL) String snapshotLabelFieldId) {
  /** Exact entrance identity and root-only conditions; physical endpoints are verified by DNA. */
  public RecordCollectionRule {
    if (!"1".equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported collection policy version");
    }
    RecordCalculationDefinition.identity(relationId);
    edgeModelId = RecordQueryLimits.modelId(edgeModelId);
    Objects.requireNonNull(edgeSchema, "edgeSchema");
    targetModelId = RecordQueryLimits.modelId(targetModelId);
    targetAppId = RecordQueryLimits.modelId(targetAppId);
    targetFormId = RecordQueryLimits.modelId(targetFormId);
    if (edgeModelId.equals(targetModelId)
        || targetResourceCode == null
        || targetResourceCode.isBlank()
        || targetResourceCode.length() > 160
        || !targetResourceCode.equals(targetResourceCode.trim())) {
      throw new IllegalArgumentException(
          "A distinct edge model and exact target entrance are required");
    }
    for (var condition : new RecordValidationCondition[] {eligibility, mutableWhen}) {
      if (condition != null) {
        condition.complexity();
        requireRoot(condition);
      }
    }
    if (snapshotLabelFieldId != null) {
      RecordCalculationDefinition.identity(snapshotLabelFieldId);
    }
  }

  /** Counts both endpoint conditions against the shared governance traversal budget. */
  public int complexity() {
    return (eligibility == null ? 0 : eligibility.complexity())
        + (mutableWhen == null ? 0 : mutableWhen.complexity());
  }

  /** Counts literal storage without weakening each condition's own complexity limit. */
  public long textBudget() {
    return (eligibility == null ? 0 : eligibility.textBudget())
        + (mutableWhen == null ? 0 : mutableWhen.textBudget());
  }

  private static void requireRoot(RecordValidationCondition condition) {
    if (condition.field() != null && !"parent".equals(condition.field().scope())) {
      throw new IllegalArgumentException("Collections only refer to independent root records");
    }
    condition.children().forEach(RecordCollectionRule::requireRoot);
  }

  static List<RecordCollectionRule> canonical(
      List<RecordCollectionRule> collections, List<RecordReferenceRule> references) {
    if (collections == null) {
      return null;
    }
    var copy = List.copyOf(collections);
    var ids = new java.util.HashSet<String>();
    var models = new java.util.HashSet<String>();
    var scalar = references == null ? List.<RecordReferenceRule>of() : references;
    scalar.forEach(rule -> ids.add(rule.relationId()));
    if (copy.size() + scalar.size() > 50
        || copy.stream()
            .anyMatch(rule -> !ids.add(rule.relationId()) || !models.add(rule.edgeModelId()))
        || copy.stream().mapToLong(RecordCollectionRule::complexity).sum()
                + scalar.stream().mapToLong(RecordReferenceRule::complexity).sum()
            > 16_384
        || copy.stream().mapToLong(RecordCollectionRule::textBudget).sum()
                + scalar.stream().mapToLong(RecordReferenceRule::textBudget).sum()
            > 65_536) {
      throw new IllegalArgumentException(
          "Collection relation identity or shared policy budget exceeded");
    }
    return copy.stream()
        .sorted(java.util.Comparator.comparing(RecordCollectionRule::relationId))
        .toList();
  }
}
