package org.simplepoint.data.datasource;

import org.simplepoint.data.datasource.context.DataSourceContextHolder;
import org.simplepoint.data.datasource.exception.DataSourceNotFoundException;
import org.simplepoint.data.datasource.properties.SimpleDataSourceConfigProperties;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Custom implementation of AbstractRoutingDataSource for dynamic data source routing.
 * This class initializes the data sources based on the provided configuration properties
 * and determines the current lookup key for routing database operations.
 */
public class SimpleRoutingDataSource extends AbstractRoutingDataSource {

  /**
   * Constructs a new SimpleRoutingDataSource instance with the given data source configuration properties.
   * Initializes and registers the data sources and sets the default data source.
   *
   * @param properties the configuration properties containing data source information
   * @throws DataSourceNotFoundException if no data source information is configured
   */
  public SimpleRoutingDataSource(SimpleDataSourceConfigProperties properties) {
    var propertiesDataSources = properties.getList();
    if (propertiesDataSources == null) {
      throw new DataSourceNotFoundException("The data source information is not configured!");
    }
    propertiesDataSources.forEach(propertiesDataSource ->
        DataSourceContextHolder.putProperties(propertiesDataSource.getName(),
            propertiesDataSource));
    this.setDataSourceLookup(new SimpleDataSourceLookup());
    super.setTargetDataSources(DataSourceContextHolder.getTargetDataSources());
    super.setDefaultTargetDataSource(properties.getDefaultName());
    super.afterPropertiesSet();
  }

  /**
   * Determines the current lookup key for routing.
   * This method retrieves the data source key from the DataSourceContextHolder.
   *
   * @return the current lookup key for data source routing
   */
  @Override
  protected Object determineCurrentLookupKey() {
    return DataSourceContextHolder.get();
  }
}
