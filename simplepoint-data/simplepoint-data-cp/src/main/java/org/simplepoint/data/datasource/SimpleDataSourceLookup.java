package org.simplepoint.data.datasource;

import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.simplepoint.data.datasource.context.DataSourceContextHolder;
import org.springframework.jdbc.datasource.lookup.DataSourceLookup;
import org.springframework.jdbc.datasource.lookup.DataSourceLookupFailureException;

/**
 * Implementation of the DataSourceLookup interface for retrieving DataSource instances
 * based on their names. This class delegates the lookup operation to the DataSourceContextHolder.
 */
public class SimpleDataSourceLookup implements DataSourceLookup {

  /**
   * Retrieves a DataSource instance by its name.
   * This method uses DataSourceContextHolder to fetch the DataSource and ensures
   * that the provided name is not null.
   *
   * @param dataSourceName the name of the DataSource to retrieve
   * @return the DataSource instance corresponding to the provided name
   * @throws DataSourceLookupFailureException if the DataSource lookup fails
   */
  @NotNull
  @Override
  public DataSource getDataSource(@NotNull String dataSourceName)
      throws DataSourceLookupFailureException {
    return DataSourceContextHolder.getDataSource(dataSourceName);
  }
}
