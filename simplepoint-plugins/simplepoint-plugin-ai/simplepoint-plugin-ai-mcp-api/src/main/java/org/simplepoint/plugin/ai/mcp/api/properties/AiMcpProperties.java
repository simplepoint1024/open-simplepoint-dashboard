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
}
