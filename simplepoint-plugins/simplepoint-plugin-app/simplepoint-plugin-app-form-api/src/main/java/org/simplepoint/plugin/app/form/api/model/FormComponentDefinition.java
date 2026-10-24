package org.simplepoint.plugin.app.form.api.model;

import java.util.List;
import java.util.Map;

/** One immutable node in a form scene tree. */
public record FormComponentDefinition(
    String componentId,
    String componentType,
    String componentVersion,
    String fieldId,
    int columnSpan,
    String label,
    String description,
    String placeholder,
    String targetSceneId,
    boolean readOnly,
    Map<String, FormValue> properties,
    List<FormComponentDefinition> children,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @org.simplepoint.api.schema.LocalizedField(maxLength = 120)
        org.simplepoint.api.locale.LocalizedText labelLocalization) {
  /** Retains source and snapshot compatibility when translated labels are absent. */
  public FormComponentDefinition(
      String componentId,
      String componentType,
      String componentVersion,
      String fieldId,
      int columnSpan,
      String label,
      String description,
      String placeholder,
      String targetSceneId,
      boolean readOnly,
      Map<String, FormValue> properties,
      List<FormComponentDefinition> children) {
    this(
        componentId,
        componentType,
        componentVersion,
        fieldId,
        columnSpan,
        label,
        description,
        placeholder,
        targetSceneId,
        readOnly,
        properties,
        children,
        null);
  }

  /** Keeps the component tree immutable. */
  public FormComponentDefinition {
    labelLocalization =
        labelLocalization == null
            ? null
            : org.simplepoint.api.locale.LocalizedTexts.validate(labelLocalization, 120, 32);
    properties = properties == null ? Map.of() : Map.copyOf(properties);
    children = children == null ? List.of() : List.copyOf(children);
  }
}
