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
    List<FormComponentDefinition> children
) {

  /** Keeps the component tree immutable. */
  public FormComponentDefinition {
    properties = properties == null ? Map.of() : Map.copyOf(properties);
    children = children == null ? List.of() : List.copyOf(children);
  }
}
