package org.simplepoint.plugin.dna.runtime.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded execution and slow-operation observation settings for Record APIs. */
@ConfigurationProperties(prefix = "simplepoint.dna.runtime.execution")
public class DnaRuntimeExecutionProperties {

  private int maxConcurrentPerTenant = 16;
  private long slowThresholdMs = 2_000L;

  public int getMaxConcurrentPerTenant() {
    return maxConcurrentPerTenant;
  }

  /** Sets the maximum concurrent Record operations admitted for one tenant. */
  public void setMaxConcurrentPerTenant(final int maxConcurrentPerTenant) {
    if (maxConcurrentPerTenant < 1 || maxConcurrentPerTenant > 1_000) {
      throw new IllegalArgumentException("maxConcurrentPerTenant must be between 1 and 1000");
    }
    this.maxConcurrentPerTenant = maxConcurrentPerTenant;
  }

  public long getSlowThresholdMs() {
    return slowThresholdMs;
  }

  /** Sets the duration after which a sanitized slow-operation warning is emitted. */
  public void setSlowThresholdMs(final long slowThresholdMs) {
    if (slowThresholdMs < 1 || slowThresholdMs > 3_600_000L) {
      throw new IllegalArgumentException("slowThresholdMs must be between 1 and 3600000");
    }
    this.slowThresholdMs = slowThresholdMs;
  }
}
