package org.simplepoint.plugin.ai.workflow.api.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Independent durable Workflow Runtime configuration.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = WorkflowExecutionProperties.PREFIX)
public class WorkflowExecutionProperties {

  public static final String PREFIX = "simplepoint.ai.workflow.execution";

  private Boolean enabled = false;

  private Duration pollInterval = Duration.ofMillis(500);

  private Duration childPollInterval = Duration.ofMillis(500);

  private Duration leaseDuration = Duration.ofMinutes(2);

  private Duration leaseHeartbeatInterval = Duration.ofSeconds(20);

  private Integer batchSize = 4;

  private Integer maximumConcurrency = 8;

  private Integer maxAttempts = 32;

  private Integer maximumPayloadBytes = 1024 * 1024;

  private Duration shutdownTimeout = Duration.ofSeconds(30);
}
