package org.simplepoint.gateway.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis-backed MCP session affinity settings for the public host Gateway.
 */
@Component
@ConfigurationProperties(prefix = "simplepoint.host.mcp-session-affinity")
public class McpSessionAffinityProperties {

  private boolean enabled = true;

  private Duration ttl = Duration.ofMinutes(10);

  private String keyPrefix = "simplepoint:mcp:sessions:";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(final Duration ttl) {
    this.ttl = ttl;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(final String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }
}
