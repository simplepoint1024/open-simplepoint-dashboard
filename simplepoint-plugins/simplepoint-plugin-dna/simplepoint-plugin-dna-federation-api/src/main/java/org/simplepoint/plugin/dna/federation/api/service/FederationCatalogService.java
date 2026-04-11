package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Collection;
import java.util.List;
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

  /**
   * Resolves all active catalogs for the supplied ids, including auto-generated datasource catalogs.
   *
   * @param ids catalog ids
   * @return resolved catalogs
   */
  List<FederationCatalog> findAllActiveByIds(Collection<String> ids);
}
