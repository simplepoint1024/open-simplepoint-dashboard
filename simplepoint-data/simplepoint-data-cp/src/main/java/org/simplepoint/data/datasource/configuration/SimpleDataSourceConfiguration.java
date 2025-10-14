package org.simplepoint.data.datasource.configuration;

import javax.sql.DataSource;
import org.simplepoint.data.datasource.SimpleRoutingDataSource;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.data.datasource.properties.SimpleDataSourceConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A configuration class for setting up a simple data source with routing capabilities.
 * Uses Spring's {@link Configuration} to define and manage the bean lifecycle.
 */
@Configuration(proxyBeanMethods = false)
public class SimpleDataSourceConfiguration {

  /**
   * Creates and configures a {@link DataSource} bean using the provided configuration properties.
   *
   * @param configProperties the configuration properties used to initialize the data source
   * @return a {@link DataSource} instance with routing capabilities
   */
  @Bean
  public DataSource dataSource(SimpleDataSourceConfigProperties configProperties) {
    // Returns a data source with routing capabilities, initialized with the given properties
    return new SimpleDataSource(new SimpleRoutingDataSource(configProperties));
  }
}
