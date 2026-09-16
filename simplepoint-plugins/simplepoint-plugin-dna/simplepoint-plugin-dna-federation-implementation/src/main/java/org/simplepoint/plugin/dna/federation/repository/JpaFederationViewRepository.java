package org.simplepoint.plugin.dna.federation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;
import org.simplepoint.plugin.dna.federation.api.repository.FederationViewRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for federation views.
 */
@Repository
public interface JpaFederationViewRepository
    extends BaseRepository<FederationView, String>, FederationViewRepository {

  @Override
  @Query("""
      select v
      from FederationView v
      where v.id = :id and v.deletedAt is null
      """)
  Optional<FederationView> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select v
      from FederationView v
      where v.code = :code and v.deletedAt is null
      """)
  Optional<FederationView> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select v
      from FederationView v
      where v.schemaId in :schemaIds and v.deletedAt is null
      """)
  List<FederationView> findAllActiveBySchemaIds(@Param("schemaIds") Collection<String> schemaIds);

  @Override
  @Query("""
      select case when count(v) > 0 then true else false end
      from FederationView v
      where v.code = :code and v.deletedAt is null
      """)
  boolean existsByCode(@Param("code") String code);
}
