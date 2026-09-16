package org.simplepoint.plugin.ai.skill.service.publish;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;

class AiSkillPublishMetricsTest {

  @Test
  void recordsOnlyBoundedOutcomeStageAndDurationDimensions() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AiSkillPublishMetrics metrics = new AiSkillPublishMetrics(registry);
    Instant started = Instant.parse("2026-08-08T00:00:00Z");

    metrics.outcome("succeeded");
    metrics.stage(SkillPublishTaskStage.COMPLETED);
    metrics.duration("succeeded", started, started.plusSeconds(3));

    assertThat(registry.get("simplepoint.ai.skill.publish.tasks")
        .tag("outcome", "succeeded").counter().count()).isEqualTo(1D);
    assertThat(registry.get("simplepoint.ai.skill.publish.stages")
        .tag("stage", "completed").counter().count()).isEqualTo(1D);
    assertThat(registry.get("simplepoint.ai.skill.publish.duration")
        .tag("outcome", "succeeded").timer().totalTime(
            java.util.concurrent.TimeUnit.SECONDS
        )).isEqualTo(3D);
  }

  @Test
  void ignoresInvalidDurationInsteadOfPublishingMisleadingLatency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AiSkillPublishMetrics metrics = new AiSkillPublishMetrics(registry);
    Instant completed = Instant.parse("2026-08-08T00:00:00Z");

    metrics.duration("failed", completed.plusSeconds(1), completed);

    assertThat(registry.find("simplepoint.ai.skill.publish.duration").timer())
        .isNull();
  }
}
