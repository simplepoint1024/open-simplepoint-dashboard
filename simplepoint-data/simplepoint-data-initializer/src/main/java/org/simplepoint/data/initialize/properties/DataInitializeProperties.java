package org.simplepoint.data.initialize.properties;


import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for data initialization.
 */
@Data
@ConfigurationProperties(prefix = "simplepoint.datainitialize")
public class DataInitializeProperties {
  private boolean enabled = true;
  private Map<String, InitializerSettings> module;
}
