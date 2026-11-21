package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.api.locale.LocalizedTexts;
import org.simplepoint.api.schema.LocalizedField;

/** A presentation of an existing owning relation; it never declares another foreign key. */
public record FormInverseRelationView(
    String viewId,
    String label,
    FormRelationTarget source,
    String sourceRelationId,
    boolean allowCreate,
    boolean allowEdit,
    boolean allowDelete,
    boolean allowUnlink,
    @JsonInclude(JsonInclude.Include.NON_NULL) @LocalizedField(maxLength = 120)
        LocalizedText labelLocalization) {

  /** Validates stable scope identities and labels; action flags are only configuration ceilings. */
  public FormInverseRelationView {
    viewId = FormRelationIdentity.require(viewId);
    sourceRelationId = FormRelationIdentity.require(sourceRelationId);
    Objects.requireNonNull(source, "An inverse view requires its exact source entry");
    if (label == null || label.isBlank() || label.codePointCount(0, label.length()) > 120) {
      throw new IllegalArgumentException("Inverse view label is invalid");
    }
    labelLocalization =
        labelLocalization == null ? null : LocalizedTexts.validate(labelLocalization, 120, 32);
    if (labelLocalization != null && !label.equals(labelLocalization.defaultValue())) {
      throw new IllegalArgumentException("Inverse view translation must retain its source label");
    }
  }
}
