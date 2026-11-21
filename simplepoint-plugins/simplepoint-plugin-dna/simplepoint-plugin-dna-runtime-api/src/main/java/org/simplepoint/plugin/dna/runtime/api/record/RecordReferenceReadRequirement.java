package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Frozen permission dependency of a copied value; contains identities, never values or grants. */
public record RecordReferenceReadRequirement(
    String modelId,
    String appId,
    String formId,
    String resourceCode,
    String recordId,
    @JsonProperty(required = true) String detailModelId,
    @JsonProperty(required = true) String detailRecordId,
    List<String> fieldIds) {
  /** A detail dependency always retains its original root record and resource boundary. */
  public RecordReferenceReadRequirement {
    modelId = RecordQueryLimits.modelId(modelId);
    appId = RecordQueryLimits.modelId(appId);
    formId = RecordQueryLimits.modelId(formId);
    recordId = canonicalRecordId(recordId);
    if (resourceCode == null
        || resourceCode.isBlank()
        || resourceCode.length() > 200
        || resourceCode.indexOf('\0') >= 0
        || !resourceCode.equals(resourceCode.trim())
        || detailModelId == null && detailRecordId != null
        || fieldIds == null
        || fieldIds.isEmpty()
        || fieldIds.size() > 128) {
      throw new IllegalArgumentException("Invalid copied value permission dependency");
    }
    if (detailModelId != null) {
      detailModelId = RecordQueryLimits.modelId(detailModelId);
      detailRecordId = detailRecordId == null ? null : canonicalRecordId(detailRecordId);
      if (detailModelId.equals(modelId)) {
        throw new IllegalArgumentException("A detail dependency requires its distinct owned model");
      }
    }
    fieldIds.forEach(RecordCalculationDefinition::identity);
    if (fieldIds.stream().distinct().count() != fieldIds.size()) {
      throw new IllegalArgumentException("A permission dependency cannot repeat fields");
    }
    fieldIds = fieldIds.stream().sorted().toList();
  }

  /** Canonical structured row identity; field unions are assembled by the trusted capture owner. */
  public List<String> rowIdentity() {
    return List.of(
        modelId,
        appId,
        formId,
        resourceCode,
        recordId,
        detailModelId == null ? "" : detailModelId,
        detailRecordId == null ? "" : detailRecordId);
  }

  private static String canonicalRecordId(String value) {
    value = RecordQueryLimits.recordId(value);
    if (!java.util.UUID.fromString(value).toString().equals(value)) {
      throw new IllegalArgumentException(
          "A permission dependency requires a canonical record identity");
    }
    return value;
  }
}
