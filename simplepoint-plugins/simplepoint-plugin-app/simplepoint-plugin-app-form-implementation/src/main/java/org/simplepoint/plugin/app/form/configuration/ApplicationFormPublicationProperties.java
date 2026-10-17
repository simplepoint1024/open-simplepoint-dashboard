package org.simplepoint.plugin.app.form.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounds the APP publication execution lease used for crash recovery. */
@ConfigurationProperties(prefix = "simplepoint.app.form.publication")
public class ApplicationFormPublicationProperties {

  private long leaseMs = 1_200_000L;

  public long getLeaseMs() {
    return leaseMs;
  }

  /** Sets the time before an unfinished APP publication can be reclaimed. */
  public void setLeaseMs(final long leaseMs) {
    if (leaseMs < 60_000L || leaseMs > 86_400_000L) {
      throw new IllegalArgumentException("leaseMs must be between 60000 and 86400000");
    }
    this.leaseMs = leaseMs;
  }
}
