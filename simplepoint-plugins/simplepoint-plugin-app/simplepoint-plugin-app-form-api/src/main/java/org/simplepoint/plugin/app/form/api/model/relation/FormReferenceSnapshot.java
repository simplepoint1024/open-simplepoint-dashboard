package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Immutable label evidence keyed by stable form identities, independent of physical columns. */
public record FormReferenceSnapshot(
    String relationId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String detailId,
    String sourceRecordId,
    String fieldId,
    String targetRecordId,
    long targetVersion,
    String labelFieldId,
    String label) {
  /**
   * Bounds portable identities and label allocation; the aggregate retains its serialized budget.
   */
  public FormReferenceSnapshot {
    relationId = FormRelationIdentity.require(relationId);
    if (detailId != null) {
      detailId = FormRelationIdentity.require(detailId);
    }
    fieldId = FormRelationIdentity.require(fieldId);
    labelFieldId = FormRelationIdentity.require(labelFieldId);
    sourceRecordId = recordId(sourceRecordId);
    targetRecordId = recordId(targetRecordId);
    if (targetVersion < 0 || label != null && label.length() > 1_048_576) {
      throw new IllegalArgumentException("Invalid reference snapshot version or label size");
    }
  }

  private static String recordId(String value) {
    if (value == null
        || value.length() != 36
        || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("Invalid reference snapshot record identity");
    }
    return UUID.fromString(value).toString();
  }
}
