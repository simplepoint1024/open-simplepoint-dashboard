package org.simplepoint.plugin.dna.core.api.spi;

import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;

/**
 * SPI for creating managed SimpleDataSource instances.
 */
public interface JdbcManagedDataSourceFactory {

  /**
   * Whether the factory supports the supplied driver definition.
   *
   * @param driver driver definition
   * @return true when supported
   */
  boolean supports(JdbcDriverDefinition driver);

  /**
   * Creates a managed datasource wrapped by {@link SimpleDataSource}.
   *
   * @param driver     driver definition
   * @param dataSource datasource definition
   * @return managed datasource
   */
  SimpleDataSource create(JdbcDriverDefinition driver, JdbcDataSourceDefinition dataSource);
}
