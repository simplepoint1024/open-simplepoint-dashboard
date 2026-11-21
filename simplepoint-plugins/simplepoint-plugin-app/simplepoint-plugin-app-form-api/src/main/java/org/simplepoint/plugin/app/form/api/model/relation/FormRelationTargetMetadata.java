package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.List;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;

/** Minimal authoring metadata; no target layout, values, storage codes or physical placement. */
public record FormRelationTargetMetadata(
    FormRelationTarget target, String pageId, int formVersion, List<Field> fields) {
  public FormRelationTargetMetadata {
    fields = List.copyOf(fields);
  }

  /** All returned fields are readable persistent scalar root fields; only text is searchable. */
  public record Field(
      String fieldId,
      String label,
      LocalizedText labelLocalization,
      FormFieldType type,
      boolean searchable) {}
}
