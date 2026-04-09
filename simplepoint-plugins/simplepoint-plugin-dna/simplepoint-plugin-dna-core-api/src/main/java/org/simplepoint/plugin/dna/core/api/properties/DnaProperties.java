package org.simplepoint.plugin.dna.core.api.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DNA plugin properties.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = DnaProperties.PREFIX)
public class DnaProperties {

  public static final String PREFIX = "simplepoint.dna";

  private String driverStoragePath = "data/dna/drivers";

  private String dialectStoragePath = "data/dna/dialects";

  private Boolean autoDownloadDriver = true;
}
