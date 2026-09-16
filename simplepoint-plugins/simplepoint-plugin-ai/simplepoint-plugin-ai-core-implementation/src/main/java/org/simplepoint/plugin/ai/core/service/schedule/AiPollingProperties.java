package org.simplepoint.plugin.ai.core.service.schedule;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Shared controls for durable AI worker polling. */
@Data
@Configuration
@ConfigurationProperties(prefix = AiPollingProperties.PREFIX)
public class AiPollingProperties {

  public static final String PREFIX = "simplepoint.ai.polling";

  private boolean adaptiveBackoffEnabled = true;

  private Duration maximumIdleInterval = Duration.ofSeconds(2);
}
