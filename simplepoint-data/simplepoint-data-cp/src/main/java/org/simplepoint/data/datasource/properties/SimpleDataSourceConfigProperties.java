package org.simplepoint.data.datasource.properties;

import java.util.Map;
import java.util.Properties;
import java.util.Set;
import lombok.Data;
import org.simplepoint.api.environment.EnvironmentConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Configuration class for setting up SimpleDataSource properties.
 * This class is annotated with @ConfigurationProperties to map external configuration properties
 * with the specified prefix to its fields. It also implements EnvironmentConfiguration to apply
 * these properties within the application's environment.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = SimpleDataSourceConfigProperties.PREFIX)
public class SimpleDataSourceConfigProperties implements EnvironmentConfiguration {

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

  /**
   * Applies datasource properties to the environment configuration.
   * This method transforms properties that start with "datasource." and adds them
   * to the simplepoint map with the "simplepoint." prefix.
   *
   * @param properties  the source properties to transform and apply
   * @param simplepoint the target map to store transformed properties
   * @param environment the application's configurable environment
   */
  @Override
  public void apply(Properties properties, Map<String, Object> simplepoint,
                    ConfigurableEnvironment environment) {
    properties.forEach((key, value) -> {
      if (String.valueOf(key).startsWith("datasource.")) {
        simplepoint.put("simplepoint." + key, value);
      }
    });
  }
}
