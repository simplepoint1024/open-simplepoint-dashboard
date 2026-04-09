package org.simplepoint.plugin.dna.federation.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.repository.FederationCatalogRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for federation catalogs.
 */
@Repository
public interface JpaFederationCatalogRepository
    extends BaseRepository<FederationCatalog, String>, FederationCatalogRepository {

  @Override
  @Query("""
      select c
      from FederationCatalog c
      where c.id = :id and c.deletedAt is null
      """)
  Optional<FederationCatalog> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select c
      from FederationCatalog c
      where c.code = :code and c.deletedAt is null
      """)
  Optional<FederationCatalog> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select case when count(c) > 0 then true else false end
      from FederationCatalog c
      where c.code = :code and c.deletedAt is null
      """)
  boolean existsByCode(@Param("code") String code);
}
