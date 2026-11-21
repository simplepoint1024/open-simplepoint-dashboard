package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Read-only owner preflight of an installed scalar reference and a prepared destination edge. */
public record RecordReferenceCollectionPreflightQuery(
    String physicalModelId,
    String appId,
    String formId,
    RecordSchemaReference schemaReference,
    long expectedPolicyVersion,
    String sourceRelationId,
    RecordCollectionRule destination) {
  public RecordReferenceCollectionPreflightQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    appId = RecordQueryLimits.modelId(appId);
    formId = RecordQueryLimits.modelId(formId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    RecordQueryLimits.writePolicyVersion(expectedPolicyVersion);
    RecordCalculationDefinition.identity(sourceRelationId);
    Objects.requireNonNull(destination, "destination");
    if (expectedPolicyVersion == 0
        || sourceRelationId.equals(destination.relationId())
        || physicalModelId.equals(destination.edgeModelId())) {
      throw new IllegalArgumentException(
          "Conversion requires an installed source and a distinct edge");
    }
  }
}
