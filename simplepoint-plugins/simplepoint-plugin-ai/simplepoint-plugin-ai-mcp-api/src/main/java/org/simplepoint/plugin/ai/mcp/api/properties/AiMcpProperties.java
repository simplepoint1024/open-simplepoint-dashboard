package org.simplepoint.plugin.ai.mcp.api.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI control-plane configuration for the independent MCP Gateway.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = AiMcpProperties.PREFIX)
public class AiMcpProperties {

  public static final String PREFIX = "simplepoint.ai.mcp";

  private String gatewayBaseUrl = "http://mcp-gateway:2890";

  private String gatewayInternalHeader = "X-SimplePoint-MCP-Gateway-Token";

  private String gatewayInternalToken;

  private Duration gatewayRequestTimeout = Duration.ofSeconds(45);

  private String publicationBaseUrl = "http://localhost:8080";

  private String publicationAuthorizationServerUrl = "http://localhost:9000";

  private boolean publicationAllowInsecureHttp;

  private boolean taskExecutionEnabled = true;

  private Duration taskDefaultTtl = Duration.ofHours(1);

  private Duration taskMaximumTtl = Duration.ofHours(24);

  private Duration taskPollInterval = Duration.ofSeconds(1);

  private Duration taskWorkerPollInterval = Duration.ofMillis(500);

  private Duration taskLeaseDuration = Duration.ofMinutes(2);

  private int taskWorkerBatchSize = 4;

  private int taskMaximumConcurrency = 8;

  private int taskMaximumAttempts = 3;

  private int taskMaximumPayloadBytes = 1_048_576;
}
