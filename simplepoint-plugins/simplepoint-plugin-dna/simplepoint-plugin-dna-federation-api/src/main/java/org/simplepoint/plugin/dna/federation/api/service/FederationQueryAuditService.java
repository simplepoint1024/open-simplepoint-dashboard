package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;

/**
 * Service contract for federation query audits.
 */
public interface FederationQueryAuditService extends BaseService<FederationQueryAudit, String> {

  /**
   * Finds an active audit record by id.
   *
   * @param id audit id
   * @return active audit record
   */
  Optional<FederationQueryAudit> findActiveById(String id);
}
