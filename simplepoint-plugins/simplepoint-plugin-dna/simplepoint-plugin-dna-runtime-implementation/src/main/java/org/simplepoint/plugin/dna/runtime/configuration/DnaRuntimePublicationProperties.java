package org.simplepoint.plugin.dna.runtime.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Safety bounds for recovering interrupted schema publications. */
@ConfigurationProperties(prefix = "simplepoint.dna.runtime.publication")
public class DnaRuntimePublicationProperties {

  private long staleAfterMs = 900_000L;

  public long getStaleAfterMs() {
    return staleAfterMs;
  }

  /** Sets the minimum inactivity period before an operator can fence a publication. */
  public void setStaleAfterMs(final long staleAfterMs) {
    if (staleAfterMs < 60_000L || staleAfterMs > 86_400_000L) {
      throw new IllegalArgumentException("staleAfterMs must be between 60000 and 86400000");
    }
    this.staleAfterMs = staleAfterMs;
  }
}
