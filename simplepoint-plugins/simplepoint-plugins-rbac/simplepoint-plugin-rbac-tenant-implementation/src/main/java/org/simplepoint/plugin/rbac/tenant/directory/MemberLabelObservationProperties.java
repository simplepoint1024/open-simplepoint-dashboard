package org.simplepoint.plugin.rbac.tenant.directory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounds each observation transaction; ordinary installations require no tuning. */
@ConfigurationProperties("simplepoint.identity.member-label-observations")
public record MemberLabelObservationProperties(
    @DefaultValue("32") int batchSize, @DefaultValue("2s") Duration delay) {
  /** Rejects configurations that could hold the directory revision lock without a useful bound. */
  public MemberLabelObservationProperties {
    if (batchSize < 1
        || batchSize > 100
        || delay == null
        || delay.compareTo(Duration.ofMillis(100)) < 0
        || delay.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Invalid directory observation limits");
    }
  }
}
