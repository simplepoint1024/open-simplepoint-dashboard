package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;

/**
 * Repository contract for federation query audits.
 */
public interface FederationQueryAuditRepository extends BaseRepository<FederationQueryAudit, String> {

  /**
   * Finds an active audit record by id.
   *
   * @param id audit id
   * @return active audit record
   */
  Optional<FederationQueryAudit> findActiveById(String id);
}
