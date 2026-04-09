package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;

/**
 * Repository contract for federation schemas.
 */
public interface FederationSchemaRepository extends BaseRepository<FederationSchema, String> {

  /**
   * Finds an active schema by id.
   *
   * @param id schema id
   * @return active schema
   */
  Optional<FederationSchema> findActiveById(String id);

  /**
   * Finds an active schema by business code.
   *
   * @param code schema code
   * @return active schema
   */
  Optional<FederationSchema> findActiveByCode(String code);

  /**
   * Finds all active schemas under one federation catalog.
   *
   * @param catalogId catalog id
   * @return active schemas
   */
  List<FederationSchema> findAllActiveByCatalogId(String catalogId);

  /**
   * Checks whether an active schema already exists for the code.
   *
   * @param code schema code
   * @return true when already exists
   */
  boolean existsByCode(String code);
}
