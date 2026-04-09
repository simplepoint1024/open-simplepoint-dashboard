package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;

/**
 * Service contract for federation catalogs.
 */
public interface FederationCatalogService extends BaseService<FederationCatalog, String> {

  /**
   * Finds an active catalog by id.
   *
   * @param id catalog id
   * @return active catalog
   */
  Optional<FederationCatalog> findActiveById(String id);

  /**
   * Finds an active catalog by business code.
   *
   * @param code catalog code
   * @return active catalog
   */
  Optional<FederationCatalog> findActiveByCode(String code);
}
