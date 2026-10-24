package org.simplepoint.plugin.dna.core.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Deployment-owned limits for each managed JDBC pool, separate from JDBC driver properties. */
@Data
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "simplepoint.dna.managed-pool")
public class ManagedJdbcPoolProperties {
  private int maximumPoolSize = 10;
  private int minimumIdle = 0;
  private long connectionTimeoutMs = 10000;
  private long idleTimeoutMs = 60000;

  /** Rejects invalid limits before creating pools or loading driver artifacts. */
  public void validate() {
    if (maximumPoolSize < 1 || maximumPoolSize > 1000 || minimumIdle < 0
        || minimumIdle > maximumPoolSize || connectionTimeoutMs < 250
        || connectionTimeoutMs > 300000 || idleTimeoutMs < 10000 || idleTimeoutMs > 3600000) {
      throw new IllegalArgumentException("Invalid DNA managed JDBC pool limits");
    }
  }
}
