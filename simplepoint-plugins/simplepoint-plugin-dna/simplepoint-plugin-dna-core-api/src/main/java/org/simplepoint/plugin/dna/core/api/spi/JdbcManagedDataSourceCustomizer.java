package org.simplepoint.plugin.dna.core.api.spi;

import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;

/**
 * SPI for decorating runtime-managed SimpleDataSource instances.
 */
public interface JdbcManagedDataSourceCustomizer {

  /**
   * Decorates a created managed datasource.
   *
   * @param driver       driver definition
   * @param dataSource   datasource definition
   * @param simpleSource created datasource
   * @return customized datasource
   */
  SimpleDataSource customize(
      JdbcDriverDefinition driver,
      JdbcDataSourceDefinition dataSource,
      SimpleDataSource simpleSource
  );
}
