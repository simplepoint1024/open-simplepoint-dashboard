package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Browser-facing record page request that never exposes a physical model identity. */
public record ApplicationFormRecordPageQuery(
    int page,
    int size,
    List<String> fieldIds,
    ApplicationFormRecordFilter filter,
    List<ApplicationFormRecordSort> sorts
) {

  /** Keeps all requested projections and orderings immutable. */
  public ApplicationFormRecordPageQuery {
    fieldIds = fieldIds == null ? List.of() : List.copyOf(fieldIds);
    sorts = sorts == null ? List.of() : List.copyOf(sorts);
  }
}
