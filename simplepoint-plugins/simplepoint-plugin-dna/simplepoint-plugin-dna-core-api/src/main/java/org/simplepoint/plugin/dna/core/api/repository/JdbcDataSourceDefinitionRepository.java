package org.simplepoint.plugin.dna.core.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;

/**
 * Repository contract for managed datasource definitions.
 */
public interface JdbcDataSourceDefinitionRepository extends BaseRepository<JdbcDataSourceDefinition, String> {

  /**
   * Finds an active datasource definition by id.
   *
   * @param id datasource id
   * @return active datasource definition
   */
  Optional<JdbcDataSourceDefinition> findActiveById(String id);

  /**
   * Finds an active datasource definition by business code.
   *
   * @param code datasource code
   * @return active datasource definition
   */
  Optional<JdbcDataSourceDefinition> findActiveByCode(String code);

  /**
   * Finds all active datasource definitions.
   *
   * @return datasource definitions
   */
  List<JdbcDataSourceDefinition> findAllActive();

  /**
   * Finds active datasource definitions using the supplied driver.
   *
   * @param driverId driver id
   * @return datasource definitions
   */
  List<JdbcDataSourceDefinition> findAllActiveByDriverId(String driverId);

  /**
   * Checks whether an active datasource definition already exists for the code.
   *
   * @param code datasource code
   * @return true when already exists
   */
  boolean existsByCode(String code);
}
