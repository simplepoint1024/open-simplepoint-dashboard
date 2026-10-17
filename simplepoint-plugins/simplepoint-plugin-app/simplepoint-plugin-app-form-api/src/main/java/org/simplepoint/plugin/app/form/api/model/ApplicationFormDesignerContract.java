package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Authorized design capabilities and the server's versioned component declarations. */
public record ApplicationFormDesignerContract(
    boolean canCreate,
    boolean canEdit,
    boolean canPublish,
    List<FormComponentDescriptor> components,
    List<FormFieldWritePolicy> writePolicies
) {

  /** Keeps component declarations immutable. */
  public ApplicationFormDesignerContract {
    components = List.copyOf(components);
    writePolicies = writePolicies == null ? List.of() : List.copyOf(writePolicies);
  }

  /** Legacy contributors do not advertise business write-policy support. */
  public ApplicationFormDesignerContract(
      boolean canCreate, boolean canEdit, boolean canPublish, List<FormComponentDescriptor> components
  ) {
    this(canCreate, canEdit, canPublish, components, List.of());
  }
}
