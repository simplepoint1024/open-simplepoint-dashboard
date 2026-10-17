package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** One node in a bounded declarative rule condition tree. */
public record FormCondition(
    FormConditionOperator operator,
    String fieldId,
    List<FormValue> values,
    List<FormCondition> children
) {

  /** Keeps values and child conditions immutable. */
  public FormCondition {
    values = values == null ? List.of() : List.copyOf(values);
    children = children == null ? List.of() : List.copyOf(children);
  }
}
