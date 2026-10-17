package org.simplepoint.plugin.app.form.api.model;

import java.util.Map;
import java.util.Set;

/** Versioned capability declaration for one trusted form component type. */
public record FormComponentDescriptor(
    String type,
    String version,
    boolean container,
    boolean fieldRequired,
    Set<FormFieldType> supportedFieldTypes,
    Map<String, FormComponentPropertyDescriptor> propertySchema,
    Set<String> allowedParentTypes,
    Set<String> allowedChildTypes
) {

  /** Keeps supported types immutable. */
  public FormComponentDescriptor {
    supportedFieldTypes = supportedFieldTypes == null
        ? Set.of() : Set.copyOf(supportedFieldTypes);
    propertySchema = propertySchema == null ? Map.of() : Map.copyOf(propertySchema);
    allowedParentTypes = allowedParentTypes == null
        ? Set.of() : Set.copyOf(allowedParentTypes);
    allowedChildTypes = allowedChildTypes == null
        ? Set.of() : Set.copyOf(allowedChildTypes);
  }
}
