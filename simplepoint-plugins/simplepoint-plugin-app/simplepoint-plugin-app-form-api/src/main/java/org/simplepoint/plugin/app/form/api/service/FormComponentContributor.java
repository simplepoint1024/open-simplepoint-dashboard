package org.simplepoint.plugin.app.form.api.service;

import java.util.Collection;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;

/** Trusted SPI contributing versioned components to the composed platform registry. */
public interface FormComponentContributor {

  /** Returns immutable descriptors owned by this contributor. */
  Collection<FormComponentDescriptor> descriptors();

  /** Validates component-owned structure after the shared document contract has been checked. */
  default void validate(org.simplepoint.plugin.app.form.api.model.FormComponentDefinition component) {
  }
}
