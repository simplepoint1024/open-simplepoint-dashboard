package org.simplepoint.plugin.app.form.api.model;

import java.util.HashSet;
import java.util.List;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView;

/**
 * List defaults and views of existing relations, independent from physical relationship ownership.
 */
public record FormPresentation(
    List<String> listFieldOrder,
    Integer labelWidth,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<FormInverseRelationView> inverseViews) {
  /** Keeps old immutable documents and runtime projections free of invented view metadata. */
  public FormPresentation(List<String> listFieldOrder, Integer labelWidth) {
    this(listFieldOrder, labelWidth, List.of());
  }

  /** Retains an immutable field order without manufacturing optional presentation values. */
  public FormPresentation {
    listFieldOrder = listFieldOrder == null ? List.of() : List.copyOf(listFieldOrder);
    inverseViews = inverseViews == null ? List.of() : List.copyOf(inverseViews);
    if (inverseViews.size() > 20
        || new HashSet<>(inverseViews.stream().map(FormInverseRelationView::viewId).toList()).size()
            != inverseViews.size()) {
      throw new IllegalArgumentException("Inverse view budget or identity is invalid");
    }
  }
}
