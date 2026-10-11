package org.simplepoint.plugin.dna.federation.service.support;

import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.service.RuntimeMetadataCacheInvalidator;
import org.springframework.stereotype.Component;

/** Bridges Runtime publication into versioned JDBC metadata and Calcite schema caches. */
@Component
public class FederationRuntimeMetadataCacheInvalidator
    implements RuntimeMetadataCacheInvalidator {

  private final FederationMetadataCacheService metadataCacheService;
  private final FederationCalciteCatalogAssembler catalogAssembler;

  /** Creates the mandatory publication cache bridge. */
  public FederationRuntimeMetadataCacheInvalidator(
      final FederationMetadataCacheService metadataCacheService,
      final FederationCalciteCatalogAssembler catalogAssembler
  ) {
    this.metadataCacheService = metadataCacheService;
    this.catalogAssembler = catalogAssembler;
  }

  @Override
  public void invalidate(final RuntimeMetadataTarget target) {
    if (target == null) {
      throw new IllegalArgumentException("Runtime metadata target is required");
    }
    metadataCacheService.invalidateDataSource(target.dataSourceId());
    catalogAssembler.invalidateDataSource(target.dataSourceId());
  }
}
