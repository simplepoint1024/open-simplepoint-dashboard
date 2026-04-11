package org.simplepoint.plugin.dna.federation.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryTemplate;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryTemplateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for federation query templates.
 */
@Repository
public interface JpaFederationQueryTemplateRepository
    extends BaseRepository<FederationQueryTemplate, String>, FederationQueryTemplateRepository {

  @Override
  @Query("""
      select t
      from FederationQueryTemplate t
      where t.id = :id and t.deletedAt is null
      """)
  Optional<FederationQueryTemplate> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select t
      from FederationQueryTemplate t
      where t.code = :code and t.deletedAt is null
      """)
  Optional<FederationQueryTemplate> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select t
      from FederationQueryTemplate t
      where t.isPublic = true and t.deletedAt is null
      """)
  List<FederationQueryTemplate> findAllActivePublic();
}
