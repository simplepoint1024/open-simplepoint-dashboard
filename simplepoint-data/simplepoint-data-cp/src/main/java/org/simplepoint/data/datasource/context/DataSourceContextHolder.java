package org.simplepoint.data.datasource.context;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.data.datasource.exception.DataSourceNotFoundException;
import org.simplepoint.data.datasource.properties.SimpleDataSourceProperties;

/**
 * A utility class for managing and switching between multiple
 * data sources within an application context.
 * Provides thread-local storage for the current data source
 * lookup key and handles dynamic configuration.
 */
@Slf4j
public class DataSourceContextHolder {

  /**
   * A thread-local variable to hold the current data source lookup key.
   */
  private static final ThreadLocal<String> lookupKey = new ThreadLocal<>();

  /**
   * A map to store configuration properties for different data sources.
   */
  private static final Map<String, SimpleDataSourceProperties> targetProperties = new HashMap<>();

  /**
   * Sets the current data source lookup key.
   *
   * @param lookupKey the lookup key for identifying the active data source
   */
  public static void set(String lookupKey) {
    DataSourceContextHolder.lookupKey.set(lookupKey);
  }

  /**
   * Retrieves the current data source lookup key.
   *
   * @return the lookup key of the active data source
   */
  public static String get() {
    return DataSourceContextHolder.lookupKey.get();
  }

  /**
   * Clears the current thread-local data source lookup key.
   */
  public static void clear() {
    DataSourceContextHolder.lookupKey.remove();
  }

  /**
   * Stores configuration properties for a specific data source.
   *
   * @param dataSourceName the name of the data source
   * @param dataSource     the configuration properties for the data source
   */
  public static void putProperties(String dataSourceName, SimpleDataSourceProperties dataSource) {
    targetProperties.put(dataSourceName, dataSource);
  }

  /**
   * Retrieves the configuration properties for a specific data source.
   *
   * @param dataSourceName the name of the data source
   * @return the {@link SimpleDataSourceProperties} associated with the data source name
   */
  public static SimpleDataSourceProperties getProperties(String dataSourceName) {
    return targetProperties.get(dataSourceName);
  }

  /**
   * Retrieves all stored data source properties.
   *
   * @return a map of all data source names and their properties
   */
  public static Map<String, SimpleDataSourceProperties> getProperties() {
    return targetProperties;
  }

  /**
   * Removes the configuration properties for a specific data source and closes it if possible.
   *
   * @param dataSourceName the name of the data source to remove
   */
  public static void remove(String dataSourceName) {
    SimpleDataSourceProperties dataSource = targetProperties.get(dataSourceName);
    if (dataSource != null) {
      if (dataSource instanceof Closeable closeable) {
        try {
          closeable.close();
        } catch (IOException e) {
          log.warn(e.getMessage());
        }
      }
      targetProperties.remove(dataSourceName);
    }
  }

  /**
   * Initializes and retrieves a {@link DataSource} for the specified data source name.
   *
   * @param dataSourceName the name of the data source
   * @return the initialized {@link DataSource} instance
   * @throws DataSourceNotFoundException if the data source is not found
   */
  public static DataSource getDataSource(String dataSourceName) {
    SimpleDataSourceProperties sourceProperties = targetProperties.get(dataSourceName);
    if (sourceProperties == null) {
      throw new DataSourceNotFoundException("DataSource not found: " + dataSourceName);
    }
    return sourceProperties.initializeDataSourceBuilder().build();
  }

  /**
   * Retrieves all target data sources and initializes them.
   *
   * @return a map of data source lookup keys and their initialized {@link DataSource} instances
   */
  public static Map<Object, Object> getTargetDataSources() {
    Map<Object, Object> targetDataSources = new HashMap<>();
    targetProperties.forEach(
        (key, value) -> targetDataSources.put(key, value.initializeDataSourceBuilder().build()));
    return targetDataSources;
  }
}
