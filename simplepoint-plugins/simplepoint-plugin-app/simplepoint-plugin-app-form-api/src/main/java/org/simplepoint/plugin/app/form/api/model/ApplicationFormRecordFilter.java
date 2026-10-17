package org.simplepoint.plugin.app.form.api.model;

import java.util.List;
import java.util.Objects;

/** Field-identity based record filter translated by APP before DNA execution. */
public record ApplicationFormRecordFilter(
    ApplicationFormRecordFilterOperator operator,
    String fieldId,
    List<Object> values,
    List<ApplicationFormRecordFilter> children
) {

  /** Keeps the bounded filter tree immutable. */
  public ApplicationFormRecordFilter {
    Objects.requireNonNull(operator, "operator");
    values = values == null ? List.of() : List.copyOf(values);
    children = children == null ? List.of() : List.copyOf(children);
  }
}
