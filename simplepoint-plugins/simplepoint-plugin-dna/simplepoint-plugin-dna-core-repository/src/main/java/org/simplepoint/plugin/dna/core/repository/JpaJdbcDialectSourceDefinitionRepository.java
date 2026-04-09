package org.simplepoint.plugin.dna.core.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDialectSourceDefinitionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for JDBC dialect source definitions.
 */
@Repository
public interface JpaJdbcDialectSourceDefinitionRepository
    extends BaseRepository<JdbcDialectSourceDefinition, String>, JdbcDialectSourceDefinitionRepository {

  @Override
  @Query("""
      select s
      from JdbcDialectSourceDefinition s
      where s.id = :id and s.deletedAt is null
      """)
  Optional<JdbcDialectSourceDefinition> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select s
      from JdbcDialectSourceDefinition s
      where s.deletedAt is null
      order by s.createdAt desc
      """)
  List<JdbcDialectSourceDefinition> findAllActive();
}
