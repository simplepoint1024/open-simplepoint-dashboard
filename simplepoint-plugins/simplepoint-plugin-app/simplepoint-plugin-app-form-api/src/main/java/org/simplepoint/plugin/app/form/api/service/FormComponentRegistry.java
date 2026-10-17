package org.simplepoint.plugin.app.form.api.service;

import java.util.Collection;
import java.util.Optional;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;

/** Trusted registry used by both document validation and designer catalog projection. */
public interface FormComponentRegistry {

  /** Returns an immutable snapshot of available descriptors. */
  Collection<FormComponentDescriptor> descriptors();

  /** Delegates component-specific invariants to trusted extensions. */
  default void validate(org.simplepoint.plugin.app.form.api.model.FormComponentDefinition component) {
  }

  /** Finds one exact registered component contract. */
  default Optional<FormComponentDescriptor> find(final String type, final String version) {
    return descriptors().stream()
        .filter(descriptor -> descriptor.type().equals(type)
            && descriptor.version().equals(version))
        .findFirst();
  }
}
