package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;

/**
 * Repository contract for federation catalogs.
 */
public interface FederationCatalogRepository extends BaseRepository<FederationCatalog, String> {

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
   * Checks whether an active catalog already exists for the code.
   *
   * @param code catalog code
   * @return true when already exists
   */
  boolean existsByCode(String code);
}
