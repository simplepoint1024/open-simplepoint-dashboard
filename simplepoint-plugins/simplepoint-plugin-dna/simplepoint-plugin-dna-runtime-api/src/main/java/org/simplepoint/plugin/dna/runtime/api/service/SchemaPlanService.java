package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;

/** Read-only schema planning contract for a model version. */
public interface SchemaPlanService {

  /** Inspects the bound database and returns a structured initial publication plan. */
  SchemaPlanView plan(String physicalModelId, int targetVersion);
}
