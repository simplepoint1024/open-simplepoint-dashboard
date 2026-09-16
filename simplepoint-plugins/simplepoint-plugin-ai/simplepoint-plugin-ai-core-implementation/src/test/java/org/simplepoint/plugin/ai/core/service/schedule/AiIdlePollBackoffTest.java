package org.simplepoint.plugin.ai.core.service.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AiIdlePollBackoffTest {

  @Test
  void emptyPollsBackOffExponentiallyAndCapAtMaximum() {
    AtomicLong now = new AtomicLong();
    AiPollingProperties properties = new AiPollingProperties();
    properties.setMaximumIdleInterval(Duration.ofSeconds(5));
    AiIdlePollBackoff backoff = new AiIdlePollBackoff(
        Duration.ofMillis(500),
        properties,
        now::get
    );

    assertThat(backoff.shouldPoll()).isTrue();
    backoff.recordResult(false);
    assertThat(backoff.shouldPoll()).isFalse();

    now.set(Duration.ofSeconds(1).toNanos());
    assertThat(backoff.shouldPoll()).isTrue();
    backoff.recordResult(false);
    now.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(backoff.shouldPoll()).isTrue();

    backoff.recordResult(false);
    now.addAndGet(Duration.ofSeconds(4).toNanos());
    assertThat(backoff.shouldPoll()).isTrue();
    backoff.recordResult(false);
    now.addAndGet(Duration.ofSeconds(5).minusNanos(1).toNanos());
    assertThat(backoff.shouldPoll()).isFalse();
    now.incrementAndGet();
    assertThat(backoff.shouldPoll()).isTrue();
  }

  @Test
  void workFoundImmediatelyResetsBackoff() {
    AtomicLong now = new AtomicLong();
    AiPollingProperties properties = new AiPollingProperties();
    AiIdlePollBackoff backoff = new AiIdlePollBackoff(
        Duration.ofSeconds(1),
        properties,
        now::get
    );

    backoff.recordResult(false);
    backoff.recordResult(true);

    assertThat(backoff.shouldPoll()).isTrue();
  }

  @Test
  void disabledBackoffAlwaysAllowsPolling() {
    AiPollingProperties properties = new AiPollingProperties();
    properties.setAdaptiveBackoffEnabled(false);
    AiIdlePollBackoff backoff = new AiIdlePollBackoff(
        Duration.ofSeconds(1),
        properties,
        () -> 0L
    );

    backoff.recordResult(false);

    assertThat(backoff.shouldPoll()).isTrue();
  }
}
