package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Owned one-to-many data definition, independent of the control used to edit its rows. */
public record FormDetailDefinition(
    String detailId,
    String storageCode,
    String label,
    List<FormFieldDefinition> fields,
    List<FormIndexDefinition> indexes) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public FormDetailDefinition {
    fields = fields == null ? List.of() : List.copyOf(fields);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
  }
}
