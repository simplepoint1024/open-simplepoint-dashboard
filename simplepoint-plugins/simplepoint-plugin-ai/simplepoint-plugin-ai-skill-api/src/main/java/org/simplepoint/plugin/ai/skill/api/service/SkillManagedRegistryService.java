package org.simplepoint.plugin.ai.skill.api.service;

import org.simplepoint.plugin.ai.skill.api.model.SkillManagedRegistryStatus;

/** Server-side managed OCI Registry configuration and connectivity boundary. */
public interface SkillManagedRegistryService {

  /** Returns credential-free managed Registry configuration. */
  SkillManagedRegistryStatus describe();

  /** Performs a bounded authenticated Registry v2 connectivity check. */
  SkillManagedRegistryStatus checkConnectivity();
}
