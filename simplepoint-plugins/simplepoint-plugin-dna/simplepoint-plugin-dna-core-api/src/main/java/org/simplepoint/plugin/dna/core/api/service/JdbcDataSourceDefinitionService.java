package org.simplepoint.plugin.dna.core.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDataSourceConnectionResult;

/**
 * Service contract for managed datasource definitions.
 */
public interface JdbcDataSourceDefinitionService extends BaseService<JdbcDataSourceDefinition, String> {

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
   * Lists all active and enabled datasource definitions.
   *
   * @return datasource definitions
   */
  java.util.List<JdbcDataSourceDefinition> listEnabledDefinitions();

  /**
   * Tests the datasource configuration and returns connection metadata.
   *
   * @param id datasource id
   * @return connection result
   */
  JdbcDataSourceConnectionResult connect(String id);

  /**
   * Returns a cached managed datasource when it already exists.
   *
   * @param id datasource id
   * @return cached datasource
   */
  Optional<SimpleDataSource> getCachedSimpleDataSource(String id);

  /**
   * Resolves or creates a managed datasource for runtime use.
   *
   * @param id datasource id
   * @return managed datasource
   */
  SimpleDataSource requireSimpleDataSource(String id);

  /**
   * Creates a non-cached datasource for runtime operations with an overridden JDBC URL.
   *
   * @param id datasource id
   * @param jdbcUrl target JDBC URL
   * @return transient datasource
   */
  SimpleDataSource createTransientSimpleDataSource(String id, String jdbcUrl);

  /**
   * Evicts a managed datasource from the runtime cache.
   *
   * @param id datasource id
   */
  void disconnect(String id);

  /**
   * Evicts all managed datasources using the supplied driver.
   *
   * @param driverId driver id
   */
  void disconnectByDriverId(String driverId);
}
