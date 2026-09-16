package org.simplepoint.plugin.ai.skill.api.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Durable Skill publication worker limits. */
@Data
@Configuration
@ConfigurationProperties(prefix = SkillPublishProperties.PREFIX)
public class SkillPublishProperties {

  public static final String PREFIX = "simplepoint.ai.skill.publish";

  private Boolean enabled = false;

  private Duration pollInterval = Duration.ofSeconds(1);

  private Duration leaseDuration = Duration.ofMinutes(2);

  private Integer maximumAttempts = 3;

  private Integer batchSize = 2;
}
