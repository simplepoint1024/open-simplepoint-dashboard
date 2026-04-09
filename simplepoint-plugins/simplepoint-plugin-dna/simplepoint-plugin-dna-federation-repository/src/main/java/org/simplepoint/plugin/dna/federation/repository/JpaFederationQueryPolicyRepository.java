package org.simplepoint.plugin.dna.federation.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for federation query policies.
 */
@Repository
public interface JpaFederationQueryPolicyRepository
    extends BaseRepository<FederationQueryPolicy, String>, FederationQueryPolicyRepository {

  @Override
  @Query("""
      select p
      from FederationQueryPolicy p
      where p.id = :id and p.deletedAt is null
      """)
  Optional<FederationQueryPolicy> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select p
      from FederationQueryPolicy p
      where p.code = :code and p.deletedAt is null
      """)
  Optional<FederationQueryPolicy> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select p
      from FederationQueryPolicy p
      where p.catalogId = :catalogId and p.deletedAt is null
      """)
  List<FederationQueryPolicy> findAllActiveByCatalogId(@Param("catalogId") String catalogId);

  @Override
  @Query("""
      select case when count(p) > 0 then true else false end
      from FederationQueryPolicy p
      where p.code = :code and p.deletedAt is null
      """)
  boolean existsByCode(@Param("code") String code);
}
