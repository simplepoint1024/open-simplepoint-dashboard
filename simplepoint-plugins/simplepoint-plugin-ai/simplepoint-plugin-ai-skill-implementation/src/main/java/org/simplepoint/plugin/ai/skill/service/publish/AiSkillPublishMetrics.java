package org.simplepoint.plugin.ai.skill.service.publish;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;
import org.springframework.stereotype.Component;

/** Low-cardinality publication metrics suitable for dashboards and alerts. */
@Component
public class AiSkillPublishMetrics {

  private final MeterRegistry registry;

  /** Creates publication metrics backed by the platform registry. */
  public AiSkillPublishMetrics(final MeterRegistry registry) {
    this.registry = registry;
  }

  /** Counts one bounded publication outcome. */
  public void outcome(final String outcome) {
    Counter.builder("simplepoint.ai.skill.publish.tasks")
        .description("Durable Skill publication task transitions")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }

  /** Counts one stage transition from the fixed publication state machine. */
  public void stage(final SkillPublishTaskStage stage) {
    Counter.builder("simplepoint.ai.skill.publish.stages")
        .description("Skill publication stage transitions")
        .tag("stage", stage.name().toLowerCase(java.util.Locale.ROOT))
        .register(registry)
        .increment();
  }

  /** Records end-to-end latency without adding task identifiers as tags. */
  public void duration(
      final String outcome,
      final Instant startedAt,
      final Instant completedAt
  ) {
    if (startedAt == null || completedAt == null
        || completedAt.isBefore(startedAt)) {
      return;
    }
    Timer.builder("simplepoint.ai.skill.publish.duration")
        .description("End-to-end Skill publication duration")
        .tag("outcome", outcome)
        .register(registry)
        .record(Duration.between(startedAt, completedAt));
  }
}
