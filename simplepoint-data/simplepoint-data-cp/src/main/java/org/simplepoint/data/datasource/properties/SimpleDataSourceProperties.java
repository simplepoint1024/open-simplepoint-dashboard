package org.simplepoint.data.datasource.properties;

import org.simplepoint.core.ApplicationContextHolder;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

/**
 * Configuration class for defining properties of a SimpleDataSource.
 * This class extends DataSourceProperties to inherit common datasource configuration
 * attributes and provides additional initialization logic for setting the class loader.
 */
public class SimpleDataSourceProperties extends DataSourceProperties {

  /**
   * Default constructor for SimpleDataSourceProperties.
   * This constructor initializes the bean class loader using the ApplicationContextHolder's class loader.
   */
  public SimpleDataSourceProperties() {
    setBeanClassLoader(ApplicationContextHolder.getClassloader());
  }
}

