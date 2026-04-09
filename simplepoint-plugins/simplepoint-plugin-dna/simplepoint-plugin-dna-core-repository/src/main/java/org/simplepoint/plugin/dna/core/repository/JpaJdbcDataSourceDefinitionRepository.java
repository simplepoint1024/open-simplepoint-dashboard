package org.simplepoint.plugin.dna.core.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDataSourceDefinitionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for managed datasource definitions.
 */
@Repository
public interface JpaJdbcDataSourceDefinitionRepository
    extends BaseRepository<JdbcDataSourceDefinition, String>, JdbcDataSourceDefinitionRepository {

  @Override
  @Query("""
      select d
      from JdbcDataSourceDefinition d
      where d.id = :id and d.deletedAt is null
      """)
  Optional<JdbcDataSourceDefinition> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select d
      from JdbcDataSourceDefinition d
      where d.code = :code and d.deletedAt is null
      """)
  Optional<JdbcDataSourceDefinition> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select d
      from JdbcDataSourceDefinition d
      where d.deletedAt is null
      """)
  List<JdbcDataSourceDefinition> findAllActive();

  @Override
  @Query("""
      select d
      from JdbcDataSourceDefinition d
      where d.driverId = :driverId and d.deletedAt is null
      """)
  List<JdbcDataSourceDefinition> findAllActiveByDriverId(@Param("driverId") String driverId);

  @Override
  @Query("""
      select case when count(d) > 0 then true else false end
      from JdbcDataSourceDefinition d
      where d.code = :code and d.deletedAt is null
      """)
  boolean existsByCode(@Param("code") String code);
}
