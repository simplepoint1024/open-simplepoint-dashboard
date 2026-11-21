package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Installed owner policy for a single-valued relation; never accepted in an ordinary record write.
 */
public record RecordReferenceRule(
    String contractVersion,
    String relationId,
    RecordValidationField source,
    String targetModelId,
    String targetAppId,
    String targetFormId,
    String targetResourceCode,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordValidationCondition eligibility,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordValidationCondition recheckWhen,
    @JsonInclude(JsonInclude.Include.NON_NULL) String snapshotLabelFieldId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RecordReferenceMapping> mappings,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean retired) {

  /** Active reference policies retain their original wire shape. */
  public RecordReferenceRule(
      String contractVersion,
      String relationId,
      RecordValidationField source,
      String targetModelId,
      String targetAppId,
      String targetFormId,
      String targetResourceCode,
      RecordValidationCondition eligibility,
      RecordValidationCondition recheckWhen,
      String snapshotLabelFieldId,
      List<RecordReferenceMapping> mappings) {
    this(
        contractVersion,
        relationId,
        source,
        targetModelId,
        targetAppId,
        targetFormId,
        targetResourceCode,
        eligibility,
        recheckWhen,
        snapshotLabelFieldId,
        mappings,
        false);
  }

  /** Existing immutable label policies retain their exact JSON when value mappings are absent. */
  public RecordReferenceRule(
      String contractVersion,
      String relationId,
      RecordValidationField source,
      String targetModelId,
      String targetAppId,
      String targetFormId,
      String targetResourceCode,
      RecordValidationCondition eligibility,
      RecordValidationCondition recheckWhen,
      String snapshotLabelFieldId) {
    this(
        contractVersion,
        relationId,
        source,
        targetModelId,
        targetAppId,
        targetFormId,
        targetResourceCode,
        eligibility,
        recheckWhen,
        snapshotLabelFieldId,
        List.of());
  }

  /** Keeps previously published reference policies byte-compatible when no label was declared. */
  public RecordReferenceRule(
      String contractVersion,
      String relationId,
      RecordValidationField source,
      String targetModelId,
      String targetAppId,
      String targetFormId,
      String targetResourceCode,
      RecordValidationCondition eligibility,
      RecordValidationCondition recheckWhen) {
    this(
        contractVersion,
        relationId,
        source,
        targetModelId,
        targetAppId,
        targetFormId,
        targetResourceCode,
        eligibility,
        recheckWhen,
        null);
  }

  /** Validates installed stable identities and bounded optional rules. */
  public RecordReferenceRule {
    if (!(retired ? "2" : "1").equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported record reference policy version");
    }
    RecordCalculationDefinition.identity(relationId);
    Objects.requireNonNull(source, "source");
    mappings = mappings == null ? List.of() : List.copyOf(mappings);
    if (retired && (!"parent".equals(source.scope()) || eligibility != null || recheckWhen != null
        || snapshotLabelFieldId != null || !mappings.isEmpty())) {
      throw new IllegalArgumentException(
          "A retired root reference only retains its storage identity");
    }
    var mappingIds = new HashSet<String>();
    var destinations = new HashSet<String>();
    if (mappings.size() > 32
        || mappings.stream()
            .anyMatch(
                mapping ->
                    !mappingIds.add(mapping.mappingId())
                        || !destinations.add(mapping.destinationFieldId())
                        || source.fieldId().equals(mapping.destinationFieldId()))) {
      throw new IllegalArgumentException(
          "Reference mapping identities or local providers conflict");
    }
    mappings =
        mappings.stream()
            .sorted(java.util.Comparator.comparing(RecordReferenceMapping::mappingId))
            .toList();
    if (snapshotLabelFieldId != null) {
      RecordCalculationDefinition.identity(snapshotLabelFieldId);
    }
    targetModelId = RecordQueryLimits.modelId(targetModelId);
    targetAppId = RecordQueryLimits.modelId(targetAppId);
    targetFormId = RecordQueryLimits.modelId(targetFormId);
    if (targetResourceCode == null
        || targetResourceCode.isBlank()
        || targetResourceCode.length() > 200
        || !targetResourceCode.equals(targetResourceCode.trim())) {
      throw new IllegalArgumentException("An exact target resource code is required");
    }
    if (eligibility != null) {
      eligibility.complexity();
    }
    if (recheckWhen != null) {
      recheckWhen.complexity();
    }
  }

  /** Reused by the containing publication's aggregate budget, including repeated subtrees. */
  public int complexity() {
    return (eligibility == null ? 0 : eligibility.complexity())
        + (recheckWhen == null ? 0 : recheckWhen.complexity());
  }

  /** Literal data is bounded together with the rest of the installed reference policy. */
  public long textBudget() {
    return (eligibility == null ? 0 : eligibility.textBudget())
        + (recheckWhen == null ? 0 : recheckWhen.textBudget());
  }
}
