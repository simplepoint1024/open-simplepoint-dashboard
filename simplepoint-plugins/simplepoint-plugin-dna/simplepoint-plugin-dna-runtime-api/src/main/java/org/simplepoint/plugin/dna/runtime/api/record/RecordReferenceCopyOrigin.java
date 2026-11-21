package org.simplepoint.plugin.dna.runtime.api.record;

/** Protected origin of a copied field; contains no business value or authorization grant. */
public record RecordReferenceCopyOrigin(
    String relationId,
    String mappingId,
    RecordReferenceMapping.Mode mode,
    String destinationFieldId,
    String mappingRevision,
    String targetModelId,
    String targetAppId,
    String targetFormId,
    String targetResourceCode,
    String targetRecordId,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) long targetVersion,
    String targetFieldId,
    java.util.List<RecordReferenceReadRequirement> requirements) {
  /** Stable identities remain separate from mutable storage codes and localized labels. */
  public RecordReferenceCopyOrigin {
    RecordCalculationDefinition.identity(relationId);
    RecordCalculationDefinition.identity(mappingId);
    java.util.Objects.requireNonNull(mode, "mode");
    RecordCalculationDefinition.identity(destinationFieldId);
    RecordCalculationDefinition.identity(targetFieldId);
    targetModelId = RecordQueryLimits.modelId(targetModelId);
    targetAppId = RecordQueryLimits.modelId(targetAppId);
    targetFormId = RecordQueryLimits.modelId(targetFormId);
    targetRecordId = RecordQueryLimits.recordId(targetRecordId);
    if (!java.util.UUID.fromString(targetRecordId).toString().equals(targetRecordId)) {
      throw new IllegalArgumentException("A canonical copy origin identity is required");
    }
    if (mappingRevision == null
        || !mappingRevision.matches("[a-f0-9]{64}")
        || targetVersion < 0
        || targetResourceCode == null
        || targetResourceCode.isBlank()
        || targetResourceCode.indexOf('\0') >= 0
        || targetResourceCode.length() > 200
        || !targetResourceCode.equals(targetResourceCode.trim())) {
      throw new IllegalArgumentException("Invalid protected reference copy origin");
    }
    if (requirements == null
        || requirements.isEmpty()
        || requirements.size() > 256
        || requirements.stream().map(RecordReferenceReadRequirement::rowIdentity).distinct().count()
            != requirements.size()) {
      throw new IllegalArgumentException("Invalid copied value permission dependency budget");
    }
    var root =
        new RecordReferenceReadRequirement(
            targetModelId,
            targetAppId,
            targetFormId,
            targetResourceCode,
            targetRecordId,
            null,
            null,
            java.util.List.of(targetFieldId));
    if (requirements.stream()
        .noneMatch(
            requirement ->
                requirement.rowIdentity().equals(root.rowIdentity())
                    && requirement.fieldIds().contains(root.fieldIds().getFirst()))) {
      throw new IllegalArgumentException(
          "Copied value permission dependencies must include their direct source");
    }
    requirements =
        requirements.stream()
            .sorted(
                java.util.Comparator.comparing(
                    requirement -> String.join("\0", requirement.rowIdentity())))
            .toList();
  }
}
