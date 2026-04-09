package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;

/**
 * Service contract for federation query policies.
 */
public interface FederationQueryPolicyService extends BaseService<FederationQueryPolicy, String> {

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
}
