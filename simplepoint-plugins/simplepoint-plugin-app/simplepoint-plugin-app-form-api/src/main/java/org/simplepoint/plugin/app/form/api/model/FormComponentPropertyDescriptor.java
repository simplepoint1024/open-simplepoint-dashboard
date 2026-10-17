package org.simplepoint.plugin.app.form.api.model;

import java.util.Set;

/** Server-side schema for one bounded component extension property. */
public record FormComponentPropertyDescriptor(
    String name,
    Set<FormValueKind> acceptedKinds,
    boolean required,
    FormValue defaultValue
) {

  /** Keeps accepted value kinds immutable. */
  public FormComponentPropertyDescriptor {
    acceptedKinds = acceptedKinds == null ? Set.of() : Set.copyOf(acceptedKinds);
  }
}
