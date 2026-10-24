package org.simplepoint.plugin.workflow.directory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded projection work; ordinary installations use these defaults without manual wiring. */
@ConfigurationProperties("simplepoint.workflow.member-labels")
public record WorkflowMemberLabelProperties(
    @DefaultValue("8") int pagesPerRun,
    @DefaultValue("500") int pagesPerBuild,
    @DefaultValue("2") int concurrentRuns,
    @DefaultValue("10s") Duration runTimeout,
    @DefaultValue("60s") Duration leaseDuration) {
  /** Rejects unbounded configuration and leases too short for one local transaction. */
  public WorkflowMemberLabelProperties {
    if (pagesPerRun < 1
        || pagesPerRun > 100
        || pagesPerBuild < pagesPerRun
        || pagesPerBuild > 10000
        || concurrentRuns < 1
        || concurrentRuns > 16
        || runTimeout == null
        || runTimeout.isNegative()
        || runTimeout.isZero()
        || runTimeout.compareTo(Duration.ofSeconds(60)) > 0
        || leaseDuration == null
        || leaseDuration.compareTo(Duration.ofSeconds(20)) < 0
        || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0
        || leaseDuration.compareTo(runTimeout.plusSeconds(10)) < 0) {
      throw new IllegalArgumentException("Invalid workflow member label work limits");
    }
  }
}
