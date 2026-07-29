package org.simplepoint.plugin.ai.skill.api.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Durable Skill workflow executor configuration.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = SkillExecutionProperties.PREFIX)
public class SkillExecutionProperties {

  public static final String PREFIX = "simplepoint.ai.skill.execution";

  private Boolean enabled = true;

  private Duration pollInterval = Duration.ofMillis(500);

  private Duration leaseDuration = Duration.ofMinutes(2);

  private Integer batchSize = 4;

  private Integer maxAttempts = 3;

  private Integer maximumPayloadBytes = 256 * 1024;
}
