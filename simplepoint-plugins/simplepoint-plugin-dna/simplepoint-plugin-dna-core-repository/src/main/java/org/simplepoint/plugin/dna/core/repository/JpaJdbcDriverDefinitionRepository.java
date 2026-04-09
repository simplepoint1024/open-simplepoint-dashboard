package org.simplepoint.plugin.dna.core.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for JDBC driver definitions.
 */
@Repository
public interface JpaJdbcDriverDefinitionRepository
    extends BaseRepository<JdbcDriverDefinition, String>, JdbcDriverDefinitionRepository {

  @Override
  @Query("""
      select d
      from JdbcDriverDefinition d
      where d.id = :id and d.deletedAt is null
      """)
  Optional<JdbcDriverDefinition> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select d
      from JdbcDriverDefinition d
      where d.code = :code and d.deletedAt is null
      """)
  Optional<JdbcDriverDefinition> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select case when count(d) > 0 then true else false end
      from JdbcDriverDefinition d
      where d.code = :code and d.deletedAt is null
      """)
  boolean existsByCode(@Param("code") String code);

  @Override
  @Query("""
      select d
      from JdbcDriverDefinition d
      where d.deletedAt is null
      order by d.createdAt desc
      """)
  List<JdbcDriverDefinition> findAllActive();
}
