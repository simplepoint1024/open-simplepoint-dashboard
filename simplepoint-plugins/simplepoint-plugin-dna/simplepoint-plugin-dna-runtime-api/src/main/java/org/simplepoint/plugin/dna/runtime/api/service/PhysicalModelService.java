package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;

/** Tenant-safe entry point for physical model identity and immutable schema versions. */
public interface PhysicalModelService {

  /** Registers a tenant-scoped draft with its initial immutable schema version. */
  PhysicalModelView register(PhysicalModelRegistrationCommand command);

  /** Reads a model only from the current authenticated tenant. */
  PhysicalModelView get(String physicalModelId);

  /** Resolves the stable model owned by one APP form for timeout recovery. */
  PhysicalModelView getByForm(String appId, String formId);

  /** Appends an immutable target schema version using optimistic locking. */
  PhysicalModelVersionView createVersion(
      String physicalModelId,
      PhysicalModelVersionCommand command
  );
}
