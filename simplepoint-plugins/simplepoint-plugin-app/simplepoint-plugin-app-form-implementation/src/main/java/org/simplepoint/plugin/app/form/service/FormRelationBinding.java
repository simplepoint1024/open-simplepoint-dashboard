package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;

/** Frozen server-resolved identity, not a browser-selected model or an authorization snapshot. */
public record FormRelationBinding(
    String relationId,
    FormRelationTarget target,
    String targetPageId,
    String targetResourceCode,
    String targetPhysicalModelId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LogicalReferenceField> targetFields,
    @JsonInclude(JsonInclude.Include.NON_NULL) String snapshotLabelFieldId) {
  /** Validates the immutable identities captured for one published relationship. */
  public FormRelationBinding {
    identity(relationId, 64);
    Objects.requireNonNull(target, "target");
    identity(targetPageId, 36);
    identity(targetResourceCode, 200);
    identity(targetPhysicalModelId, 64);
    targetFields = LogicalReferenceDefinition.canonicalTargetFields(targetFields);
    if (snapshotLabelFieldId != null) {
      identity(snapshotLabelFieldId, 64);
      if (targetFields.stream()
          .noneMatch(
              field ->
                  field.fieldId().equals(snapshotLabelFieldId)
                      && (field.type()
                              == org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType
                                  .STRING
                          || field.type()
                              == org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType
                                  .TEXT))) {
        throw new IllegalArgumentException("Snapshot label must be a frozen text dependency");
      }
    }
  }

  /** Existing dependency snapshots do not retroactively enable label capture. */
  public FormRelationBinding(
      String relationId,
      FormRelationTarget target,
      String targetPageId,
      String targetResourceCode,
      String targetPhysicalModelId,
      List<LogicalReferenceField> targetFields) {
    this(
        relationId,
        target,
        targetPageId,
        targetResourceCode,
        targetPhysicalModelId,
        targetFields,
        null);
  }

  /** Legacy frozen bindings retain their original serialized representation. */
  public FormRelationBinding(
      String relationId,
      FormRelationTarget target,
      String targetPageId,
      String targetResourceCode,
      String targetPhysicalModelId) {
    this(relationId, target, targetPageId, targetResourceCode, targetPhysicalModelId, List.of());
  }

  private static void identity(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || !value.equals(value.trim())) {
      throw new IllegalArgumentException("Invalid frozen relation identity");
    }
  }
}
