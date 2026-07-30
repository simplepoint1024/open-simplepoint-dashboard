package org.simplepoint.plugin.ai.agent.api.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Durable Agent runtime worker configuration.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = AgentExecutionProperties.PREFIX)
public class AgentExecutionProperties {

  public static final String PREFIX = "simplepoint.ai.agent.execution";

  private Boolean enabled = false;

  private Duration pollInterval = Duration.ofMillis(500);

  private Duration skillPollInterval = Duration.ofMillis(500);

  private Duration leaseDuration = Duration.ofMinutes(2);

  private Integer batchSize = 4;

  private Integer maxAttempts = 8;

  private Integer maximumPayloadBytes = 256 * 1024;
}
