package org.simplepoint.plugin.workflow.directory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Polling and recovery defaults are independent of the bounded projection page budget. */
@ConfigurationProperties("simplepoint.workflow.member-label-refresh")
public record WorkflowMemberLabelRefreshProperties(
    @DefaultValue("2") int batchSize,
    @DefaultValue("2s") Duration delay,
    @DefaultValue("60s") Duration reconcileInterval,
    @DefaultValue("60s") Duration leaseDuration) {
  /** Every unit of work is bounded and a crashed owner eventually releases its durable claim. */
  public WorkflowMemberLabelRefreshProperties {
    if (batchSize < 1
        || batchSize > 32
        || delay == null
        || reconcileInterval == null
        || leaseDuration == null
        || delay.compareTo(Duration.ofMillis(100)) < 0
        || delay.compareTo(Duration.ofMinutes(1)) > 0
        || reconcileInterval.compareTo(Duration.ofSeconds(10)) < 0
        || reconcileInterval.compareTo(Duration.ofHours(1)) > 0
        || leaseDuration.compareTo(Duration.ofSeconds(20)) < 0
        || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Invalid member label refresh limits");
    }
  }
}
