package org.simplepoint.plugin.dna.federation.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryAuditRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for federation query audits.
 */
@Repository
public interface JpaFederationQueryAuditRepository
    extends BaseRepository<FederationQueryAudit, String>, FederationQueryAuditRepository {

  @Override
  @Query("""
      select a
      from FederationQueryAudit a
      where a.id = :id and a.deletedAt is null
      """)
  Optional<FederationQueryAudit> findActiveById(@Param("id") String id);
}
