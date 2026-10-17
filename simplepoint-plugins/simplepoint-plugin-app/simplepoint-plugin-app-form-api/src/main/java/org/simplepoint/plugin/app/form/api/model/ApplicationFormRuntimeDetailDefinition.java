package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Browser-safe owned collection definition; physical model identity never leaves the server. */
public record ApplicationFormRuntimeDetailDefinition(
    String detailId,
    String label,
    List<ApplicationFormRuntimeFieldDefinition> fields,
    List<String> readOnlyFieldIds) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public ApplicationFormRuntimeDetailDefinition {
    fields = List.copyOf(fields);
    readOnlyFieldIds = List.copyOf(readOnlyFieldIds);
  }
}
