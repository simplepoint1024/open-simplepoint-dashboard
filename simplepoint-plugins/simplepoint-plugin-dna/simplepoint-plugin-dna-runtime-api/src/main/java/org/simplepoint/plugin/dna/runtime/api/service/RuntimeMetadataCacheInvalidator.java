package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;

/** Required bridge for invalidating JDBC metadata and Calcite schema caches. */
public interface RuntimeMetadataCacheInvalidator {

  /** Invalidates every cached view that can contain the supplied physical table. */
  void invalidate(RuntimeMetadataTarget target);
}
