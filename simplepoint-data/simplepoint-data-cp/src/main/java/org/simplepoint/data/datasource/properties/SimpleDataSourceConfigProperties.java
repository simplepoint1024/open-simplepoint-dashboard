package org.simplepoint.data.datasource.properties;

import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for setting up SimpleDataSource properties.
 * This class is annotated with @ConfigurationProperties to map external configuration properties
 * with the specified prefix to its fields. It also implements EnvironmentConfiguration to apply
 * these properties within the application's environment.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = SimpleDataSourceConfigProperties.PREFIX)
public class SimpleDataSourceConfigProperties {

  /**
   * The prefix for datasource-related configuration properties.
   */
  public static final String PREFIX = "simplepoint.datasource";

  /**
   * The default name for the data source.
   */
  private String defaultName;

  /**
   * A set of properties for multiple SimpleDataSources.
   */
  private Set<SimpleDataSourceProperties> list;
}
