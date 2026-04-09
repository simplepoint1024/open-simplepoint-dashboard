package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;

/**
 * Repository contract for federation query policies.
 */
public interface FederationQueryPolicyRepository extends BaseRepository<FederationQueryPolicy, String> {

  /**
   * Finds an active query policy by id.
   *
   * @param id policy id
   * @return active query policy
   */
  Optional<FederationQueryPolicy> findActiveById(String id);

  /**
   * Finds an active query policy by business code.
   *
   * @param code policy code
   * @return active query policy
   */
  Optional<FederationQueryPolicy> findActiveByCode(String code);

  /**
   * Finds all active query policies under one federation catalog.
   *
   * @param catalogId catalog id
   * @return active query policies
   */
  List<FederationQueryPolicy> findAllActiveByCatalogId(String catalogId);

  /**
   * Checks whether an active query policy already exists for the code.
   *
   * @param code policy code
   * @return true when already exists
   */
  boolean existsByCode(String code);
}
