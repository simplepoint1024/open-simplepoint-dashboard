package org.simplepoint.plugin.dna.federation.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for federation schemas.
 */
@Repository
public interface JpaFederationSchemaRepository
    extends BaseRepository<FederationSchema, String>, FederationSchemaRepository {

  @Override
  @Query("""
      select s
      from FederationSchema s
      where s.id = :id and s.deletedAt is null
      """)
  Optional<FederationSchema> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select s
      from FederationSchema s
      where s.code = :code and s.deletedAt is null
      """)
  Optional<FederationSchema> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select s
      from FederationSchema s
      where s.catalogId = :catalogId and s.deletedAt is null
      """)
  List<FederationSchema> findAllActiveByCatalogId(@Param("catalogId") String catalogId);

  @Override
  @Query("""
      select case when count(s) > 0 then true else false end
      from FederationSchema s
      where s.code = :code and s.deletedAt is null
      """)
  boolean existsByCode(@Param("code") String code);
}
