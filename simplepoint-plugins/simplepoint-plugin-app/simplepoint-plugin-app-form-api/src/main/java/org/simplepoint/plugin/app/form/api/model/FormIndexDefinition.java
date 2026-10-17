package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Stable ordinary or unique index over persistent form field identities. */
public record FormIndexDefinition(
    String indexId,
    String storageCode,
    boolean unique,
    List<String> fieldIds
) {

  /** Keeps the ordered index field list immutable. */
  public FormIndexDefinition {
    fieldIds = fieldIds == null ? List.of() : List.copyOf(fieldIds);
  }
}
