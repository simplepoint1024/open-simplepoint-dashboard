package org.simplepoint.plugin.app.form.component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormComponentPropertyDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;

/** A bounded row/column matrix owns structural cells; it never defines physical storage. */
public final class MatrixComponentContributor implements FormComponentContributor {
  public static final String MATRIX = "layout.matrix";
  public static final String CELL = "layout.cell";

  @Override
  public Collection<FormComponentDescriptor> descriptors() {
    return List.of(
        new FormComponentDescriptor(
            MATRIX,
            "1",
            true,
            false,
            Set.of(),
            Map.of("rows", sizeProperty("rows"), "columns", sizeProperty("columns")),
            Set.of(),
            Set.of(CELL)),
        new FormComponentDescriptor(
            CELL, "1", true, false, Set.of(), Map.of(), Set.of(MATRIX), Set.of()));
  }

  @Override
  public void validate(final FormComponentDefinition component) {
    if (!MATRIX.equals(component.componentType())) {
      return;
    }
    int rows = size(component, "rows");
    int columns = size(component, "columns");
    if (component.children().size() != rows * columns
        || component.children().stream().anyMatch(child -> !CELL.equals(child.componentType()))) {
      throw invalid("Matrix cells must match its row and column count");
    }
  }

  private static FormComponentPropertyDescriptor sizeProperty(final String name) {
    return new FormComponentPropertyDescriptor(
        name, Set.of(FormValueKind.INTEGER), true, new FormValue(FormValueKind.INTEGER, "2"));
  }

  private static int size(final FormComponentDefinition component, final String name) {
    FormValue value = component.properties().get(name);
    if (value == null
        || value.kind() != FormValueKind.INTEGER
        || value.value() == null
        || !value.value().matches("[1-6]")) {
      throw invalid("Matrix dimensions must be integers between 1 and 6");
    }
    return Integer.parseInt(value.value());
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
