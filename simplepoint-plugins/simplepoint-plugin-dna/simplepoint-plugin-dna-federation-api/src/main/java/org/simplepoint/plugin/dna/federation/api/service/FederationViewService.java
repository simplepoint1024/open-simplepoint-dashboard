package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;

/**
 * Service contract for federation views.
 */
public interface FederationViewService extends BaseService<FederationView, String> {

  /**
   * Finds an active view by id.
   *
   * @param id view id
   * @return active view
   */
  Optional<FederationView> findActiveById(String id);

  /**
   * Finds an active view by business code.
   *
   * @param code view code
   * @return active view
   */
  Optional<FederationView> findActiveByCode(String code);
}
